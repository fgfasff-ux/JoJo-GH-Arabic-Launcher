package com.jojo.gh.arabic.launcher;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * محرك الحقن الخفيف المحلي للعبة BrownDust II (On-Device Lightweight SQLite Patcher):
 * يستقبل ملف القاموس المدمج الصغير (browndust2_ar.json) ويطبّقه مباشرة على قواعد بيانات
 * Data/ و Data/t/ المشفّرة الموجودة أصلاً على جهاز المستخدم:
 * 1) فك تشفير AES-256-CBC بتدفّق مباشر لكل صفحة (4096 بايت) دون استهلاك الذاكرة (Zero-OOM).
 * 2) تحديث صفوف SQLite وحقول ProtoBuf الإنجليزية بدقة (معرّف القاعدة + معرّف الجدول + معرّف السطر + قاموس OTA احتياطي).
 * 3) VACUUM + تقليص حقول Overflow غير الإنجليزية عند الحاجة + حشو صفري لمطابقة الحجم الأصلي بايت ببايت (100%).
 * 4) إعادة التشفير بنفس المفتاح والـ IV + فحص سلامة Round-Trip (PRAGMA integrity_check == ok) + حفظ النسخة الاحتياطية والطابع الزمني.
 */
final class BrownDust2PatchEngine {

    private static final int PAGE_SIZE = 4096;
    private static final int PBKDF2_ITERS = 2010;
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);

    private static final Set<String> SKIP_TEXT_TABLES = new HashSet<>(Arrays.asList(
            "idcardtextcolorttable",
            "textdescriptiontable",
            "tooltiptextdescriptiontable"
    ));

    private static final String[] SKIP_LANG_SUFFIXES = {"_cn", "_jp", "_tw", "_kr"};

    private static volatile SecretKeySpec cachedKeySpec;
    private static volatile byte[] cachedEncryptedHeader;

    private BrownDust2PatchEngine() {
    }

    static final class LightPatchResult {
        int patchedFiles;
        int patchedRows;
        int skippedFiles;
        int failedFiles;
    }

    private static final class TableSpec {
        final List<String> sqlCols;
        final int[] pbFields;
        final int[] trimOverflowFields;

        TableSpec(List<String> sqlCols, int[] pbFields, int[] trimOverflowFields) {
            this.sqlCols = sqlCols;
            this.pbFields = pbFields;
            this.trimOverflowFields = trimOverflowFields;
        }
    }

    private static final class RowPatchValue {
        final String single;
        final String textVal;
        final String nodeVal;

        RowPatchValue(String single, String textVal, String nodeVal) {
            this.single = single;
            this.textVal = textVal;
            this.nodeVal = nodeVal;
        }
    }

    private static final class ModifiedPbRow {
        final String table;
        final long rowid;
        final byte[] origBlob;
        final int[] pbFields;
        final int[] trimFields;
        final Map<Integer, String> fixedMap;

        ModifiedPbRow(String table, long rowid, byte[] origBlob, int[] pbFields, int[] trimFields, Map<Integer, String> fixedMap) {
            this.table = table;
            this.rowid = rowid;
            this.origBlob = origBlob;
            this.pbFields = pbFields;
            this.trimFields = trimFields;
            this.fixedMap = fixedMap;
        }
    }

    private static final class LightPatchPayload {
        boolean matchOriginalSize = true;
        // relPath ("Data/t/...") -> tableName -> rowIdStr -> RowPatchValue
        final Map<String, Map<String, Map<String, RowPatchValue>>> files = new LinkedHashMap<>();
        // fallback English -> shaped Arabic
        final Map<String, String> dict = new HashMap<>();
    }

    private static final class SingleDbPatchResult {
        boolean modified;
        int patchedRows;
        long origSize;
        long finalSize;
    }

    // =========================================================================
    // 1) اشتقاق المفتاح وتشفير/فك تشفير AES-256-CBC بالتدفّق لكل صفحة (4096B)
    // =========================================================================

    private static synchronized SecretKeySpec getKeySpec() throws Exception {
        if (cachedKeySpec == null) {
            char[] pass = SecretVault.bd2DbPassword().toCharArray();
            PBEKeySpec pbe = new PBEKeySpec(pass, SQLITE_MAGIC, PBKDF2_ITERS, 256);
            Arrays.fill(pass, '\0');
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            byte[] keyBytes = skf.generateSecret(pbe).getEncoded();
            pbe.clearPassword();
            cachedKeySpec = new SecretKeySpec(keyBytes, "AES");
        }
        return cachedKeySpec;
    }

    private static synchronized byte[] getExpectedEncryptedHeader() throws Exception {
        if (cachedEncryptedHeader == null) {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getKeySpec(), new IvParameterSpec(SQLITE_MAGIC));
            cachedEncryptedHeader = cipher.doFinal(SQLITE_MAGIC);
        }
        return cachedEncryptedHeader;
    }

    static boolean isEncryptedBd2SqliteFile(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        long len = file.length();
        if (len < PAGE_SIZE || (len % PAGE_SIZE) != 0) {
            return false;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] head = new byte[16];
            if (readFully(in, head, 16) != 16) {
                return false;
            }
            return Arrays.equals(head, getExpectedEncryptedHeader());
        } catch (Throwable t) {
            return false;
        }
    }

    private static int readFully(FileInputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        return off;
    }

    private static void decryptFileStreaming(File encSrc, File plainDst) throws Exception {
        long len = encSrc.length();
        if (len < PAGE_SIZE || (len % PAGE_SIZE) != 0) {
            throw new IOException("حجم قاعدة بيانات BD2 غير صالح (ليس مضاعف 4096): " + len);
        }
        SecretKeySpec key = getKeySpec();
        IvParameterSpec iv = new IvParameterSpec(SQLITE_MAGIC);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");

        byte[] inBuf = new byte[PAGE_SIZE];
        byte[] outBuf = new byte[PAGE_SIZE];
        boolean firstPage = true;

        try (FileInputStream in = new FileInputStream(encSrc);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(plainDst), 65536)) {
            while (true) {
                int n = readFully(in, inBuf, PAGE_SIZE);
                if (n == 0) {
                    break;
                }
                if (n != PAGE_SIZE) {
                    throw new IOException("صفحة ناقصة أثناء فك التشفير: " + n);
                }
                cipher.init(Cipher.DECRYPT_MODE, key, iv);
                int decLen = cipher.doFinal(inBuf, 0, PAGE_SIZE, outBuf, 0);
                if (firstPage) {
                    for (int i = 0; i < SQLITE_MAGIC.length; i++) {
                        if (outBuf[i] != SQLITE_MAGIC[i]) {
                            throw new IOException("ترويسة SQLite غير صالحة بعد فك التشفير");
                        }
                    }
                    firstPage = false;
                }
                out.write(outBuf, 0, decLen);
            }
            out.flush();
        }
    }

    private static void encryptFileStreaming(File plainSrc, File encDst) throws Exception {
        long len = plainSrc.length();
        if (len < PAGE_SIZE || (len % PAGE_SIZE) != 0) {
            throw new IOException("حجم SQLite غير صالح لإعادة التشفير (ليس مضاعف 4096): " + len);
        }
        SecretKeySpec key = getKeySpec();
        IvParameterSpec iv = new IvParameterSpec(SQLITE_MAGIC);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");

        byte[] inBuf = new byte[PAGE_SIZE];
        byte[] outBuf = new byte[PAGE_SIZE];
        boolean firstPage = true;

        try (FileInputStream in = new FileInputStream(plainSrc);
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(encDst), 65536)) {
            while (true) {
                int n = readFully(in, inBuf, PAGE_SIZE);
                if (n == 0) {
                    break;
                }
                if (n != PAGE_SIZE) {
                    throw new IOException("صفحة ناقصة أثناء إعادة التشفير: " + n);
                }
                if (firstPage) {
                    for (int i = 0; i < SQLITE_MAGIC.length; i++) {
                        if (inBuf[i] != SQLITE_MAGIC[i]) {
                            throw new IOException("ملف الإدخال ليس قاعدة SQLite 3 صالحة");
                        }
                    }
                    firstPage = false;
                }
                cipher.init(Cipher.ENCRYPT_MODE, key, iv);
                int encLen = cipher.doFinal(inBuf, 0, PAGE_SIZE, outBuf, 0);
                out.write(outBuf, 0, encLen);
            }
            out.flush();
        }
    }

    // =========================================================================
    // 2) قراءة ملف القاموس الخفيف (browndust2_ar.json)
    // =========================================================================

    private static LightPatchPayload parsePatchJson(File jsonFile) throws Exception {
        byte[] raw;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(jsonFile));
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(jsonFile.length(), 8 * 1024 * 1024))) {
            byte[] buf = new byte[32768];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            raw = out.toByteArray();
        }
        JSONObject root = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        LightPatchPayload payload = new LightPatchPayload();

        if (root.has("files") || root.has("dict") || "rdav_bd2_light_patch_v1".equals(root.optString("format", ""))) {
            payload.matchOriginalSize = root.optBoolean("match_original_size", true);

            JSONObject filesObj = root.optJSONObject("files");
            if (filesObj != null) {
                Iterator<String> fKeys = filesObj.keys();
                while (fKeys.hasNext()) {
                    String rawKey = fKeys.next();
                    JSONObject tblsObj = filesObj.optJSONObject(rawKey);
                    if (tblsObj == null) {
                        continue;
                    }
                    String rel = ShellBridgeServer.normalizeStageRelativePath(rawKey);
                    Map<String, Map<String, RowPatchValue>> tblMap = new LinkedHashMap<>();
                    Iterator<String> tKeys = tblsObj.keys();
                    while (tKeys.hasNext()) {
                        String tblName = tKeys.next();
                        JSONObject rowsObj = tblsObj.optJSONObject(tblName);
                        if (rowsObj == null) {
                            continue;
                        }
                        Map<String, RowPatchValue> rowMap = new HashMap<>();
                        Iterator<String> rKeys = rowsObj.keys();
                        while (rKeys.hasNext()) {
                            String rId = rKeys.next();
                            Object val = rowsObj.opt(rId);
                            if (val instanceof String) {
                                String s = (String) val;
                                if (!s.isEmpty()) {
                                    rowMap.put(rId, new RowPatchValue(s, null, null));
                                }
                            } else if (val instanceof JSONObject) {
                                JSONObject jo = (JSONObject) val;
                                String t = jo.has("t") ? jo.optString("t", null) : null;
                                String n = jo.has("n") ? jo.optString("n", null) : null;
                                if ((t != null && !t.isEmpty()) || (n != null && !n.isEmpty())) {
                                    rowMap.put(rId, new RowPatchValue(null, t, n));
                                }
                            }
                        }
                        if (!rowMap.isEmpty()) {
                            tblMap.put(tblName, rowMap);
                        }
                    }
                    if (!tblMap.isEmpty()) {
                        payload.files.put(rel, tblMap);
                    }
                }
            }

            JSONObject dictObj = root.optJSONObject("dict");
            if (dictObj != null) {
                Iterator<String> dKeys = dictObj.keys();
                while (dKeys.hasNext()) {
                    String k = dKeys.next();
                    String v = dictObj.optString(k, "");
                    if (k != null && !k.isEmpty() && !v.isEmpty()) {
                        payload.dict.put(k, v);
                    }
                }
            }
        } else {
            // دعم مباشر لملف قاموس مسطّح { "English": "Arabic" }
            payload.matchOriginalSize = true;
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object val = root.opt(k);
                if (val instanceof String) {
                    String v = ((String) val).trim();
                    if (k != null && !k.isEmpty() && !v.isEmpty()) {
                        payload.dict.put(k, v);
                    }
                }
            }
        }
        return payload;
    }

    // =========================================================================
    // 3) محرك تعديل حقول ProtoBuf الخام (مطابق 100% للنسخة البايثونية)
    // =========================================================================

    private static final class VarintRead {
        long value;
        int nextPos;
        boolean ok;
    }

    private static VarintRead readVarint(byte[] buf, int pos) {
        VarintRead res = new VarintRead();
        long val = 0;
        int shift = 0;
        int end = buf.length;
        while (pos < end && shift < 64) {
            int b = buf[pos++] & 0xFF;
            val |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                res.value = val;
                res.nextPos = pos;
                res.ok = true;
                return res;
            }
            shift += 7;
        }
        res.ok = false;
        res.nextPos = pos;
        return res;
    }

    private static void writeVarint(ByteArrayOutputStream out, long val) {
        long v = val;
        while (true) {
            int b = (int) (v & 0x7FL);
            v >>>= 7;
            if (v != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                break;
            }
        }
    }

    private static boolean containsInt(int[] arr, int val) {
        if (arr == null) {
            return false;
        }
        for (int x : arr) {
            if (x == val) {
                return true;
            }
        }
        return false;
    }

    private static final class PbPatchResult {
        final byte[] bytes;
        final int hits;
        final boolean changed;

        PbPatchResult(byte[] bytes, int hits, boolean changed) {
            this.bytes = bytes;
            this.hits = hits;
            this.changed = changed;
        }
    }

    private static PbPatchResult patchProtobufFields(
            byte[] blob,
            int[] targetFields,
            Map<Integer, String> fixedReplacements,
            Map<String, String> dictFallback,
            int[] trimFields
    ) {
        if (blob == null || blob.length == 0 || targetFields == null || targetFields.length == 0) {
            return new PbPatchResult(blob, 0, false);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(blob.length + 64);
        int pos = 0;
        int end = blob.length;
        int hits = 0;
        boolean changed = false;

        while (pos < end) {
            int tagStart = pos;
            VarintRead tagR = readVarint(blob, pos);
            if (!tagR.ok || tagR.value == 0) {
                return new PbPatchResult(blob, 0, false);
            }
            int fieldNum = (int) (tagR.value >>> 3);
            int wireType = (int) (tagR.value & 0x07L);
            int tagEnd = tagR.nextPos;
            pos = tagEnd;

            if (wireType == 0) {
                int vStart = pos;
                VarintRead vr = readVarint(blob, pos);
                if (!vr.ok) {
                    return new PbPatchResult(blob, 0, false);
                }
                pos = vr.nextPos;
                out.write(blob, tagStart, tagEnd - tagStart);
                out.write(blob, vStart, pos - vStart);
            } else if (wireType == 1) {
                if (pos + 8 > end) {
                    return new PbPatchResult(blob, 0, false);
                }
                out.write(blob, tagStart, tagEnd - tagStart);
                out.write(blob, pos, 8);
                pos += 8;
            } else if (wireType == 2) {
                VarintRead lr = readVarint(blob, pos);
                if (!lr.ok || lr.value < 0 || lr.value > end - lr.nextPos) {
                    return new PbPatchResult(blob, 0, false);
                }
                int ln = (int) lr.value;
                pos = lr.nextPos;
                int chunkStart = pos;
                pos += ln;

                byte[] newChunk = null;
                if (containsInt(targetFields, fieldNum) && ln > 0) {
                    String cur = new String(blob, chunkStart, ln, StandardCharsets.UTF_8);
                    String rep = null;
                    if (fixedReplacements != null) {
                        rep = fixedReplacements.get(fieldNum);
                    }
                    if (rep == null && dictFallback != null && !dictFallback.isEmpty()) {
                        rep = dictFallback.get(cur);
                        if (rep == null) {
                            rep = dictFallback.get(cur.trim());
                        }
                    }
                    if (rep != null && !rep.isEmpty() && !rep.equals(cur)) {
                        newChunk = rep.getBytes(StandardCharsets.UTF_8);
                        hits++;
                        changed = true;
                    }
                } else if (containsInt(trimFields, fieldNum) && ln > 0) {
                    newChunk = new byte[0];
                    changed = true;
                }

                out.write(blob, tagStart, tagEnd - tagStart);
                if (newChunk != null) {
                    writeVarint(out, newChunk.length);
                    if (newChunk.length > 0) {
                        out.write(newChunk, 0, newChunk.length);
                    }
                } else {
                    writeVarint(out, ln);
                    if (ln > 0) {
                        out.write(blob, chunkStart, ln);
                    }
                }
            } else if (wireType == 5) {
                if (pos + 4 > end) {
                    return new PbPatchResult(blob, 0, false);
                }
                out.write(blob, tagStart, tagEnd - tagStart);
                out.write(blob, pos, 4);
                pos += 4;
            } else {
                return new PbPatchResult(blob, 0, false);
            }
        }

        return new PbPatchResult(changed ? out.toByteArray() : blob, hits, changed);
    }

    // =========================================================================
    // 4) مواصفات الجداول + حقن SQLite + VACUUM + مطابقة الحجم بايت ببايت
    // =========================================================================

    private static TableSpec getTableSpec(String tableName, List<String> cols, Set<String> allTablesLower) {
        if (tableName == null || tableName.isEmpty() || tableName.startsWith("sqlite_")) {
            return null;
        }
        String tl = tableName.toLowerCase(Locale.US);
        if (SKIP_TEXT_TABLES.contains(tl)) {
            return null;
        }
        for (String suf : SKIP_LANG_SUFFIXES) {
            if (tl.endsWith(suf)) {
                return null;
            }
        }
        if (allTablesLower.contains(tl + "_en")) {
            return null;
        }

        Set<String> colSet = new HashSet<>(cols);
        if ("introtexttable_en".equals(tl)) {
            List<String> sc = colSet.contains("text") ? Collections.singletonList("text") : Collections.<String>emptyList();
            return new TableSpec(sc, new int[0], new int[0]);
        }
        if ("skilltexttable_en".equals(tl)) {
            List<String> sc = new ArrayList<>();
            if (colSet.contains("text")) sc.add("text");
            if (colSet.contains("nodeAddText")) sc.add("nodeAddText");
            int[] pbf = colSet.contains("ProtoBuf") ? new int[]{3, 5} : new int[0];
            return new TableSpec(sc, pbf, new int[0]);
        }
        if ("monsterhuntchangetexttable_en".equals(tl)) {
            List<String> sc = colSet.contains("text") ? Collections.singletonList("text") : Collections.<String>emptyList();
            int[] pbf = colSet.contains("ProtoBuf") ? new int[]{3} : new int[0];
            return new TableSpec(sc, pbf, new int[0]);
        }
        if (tl.endsWith("_en")) {
            List<String> sc = new ArrayList<>();
            for (String c : cols) {
                String cl = c.toLowerCase(Locale.US);
                if ("text".equals(cl) || "nodeaddtext".equals(cl) || "title".equals(cl) || "name".equals(cl) || "desc".equals(cl)) {
                    sc.add(c);
                }
            }
            int[] pbf = colSet.contains("ProtoBuf") ? new int[]{3, 5} : new int[0];
            if (!sc.isEmpty() || pbf.length > 0) {
                return new TableSpec(sc, pbf, new int[0]);
            }
            return null;
        }
        if (tl.contains("text") && colSet.contains("ProtoBuf")) {
            return new TableSpec(Collections.<String>emptyList(), new int[]{5}, new int[]{7, 4, 6});
        }
        return null;
    }

    private static void execPragmaQuiet(SQLiteDatabase db, String sql) {
        try (Cursor c = db.rawQuery(sql, null)) {
            c.moveToFirst();
        } catch (Throwable ignored) {
        }
    }

    private static void deleteSqliteAuxFiles(File dbFile) {
        String base = dbFile.getAbsolutePath();
        new File(base + "-journal").delete();
        new File(base + "-wal").delete();
        new File(base + "-shm").delete();
    }

    private static List<String> listTables(SQLiteDatabase db) {
        List<String> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)) {
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    private static List<String> listColumns(SQLiteDatabase db, String table) {
        List<String> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(\"" + table + "\")", null)) {
            while (c.moveToNext()) {
                String n = c.getString(1);
                if (n != null) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    private static SingleDbPatchResult patchSingleEncryptedDb(
            File srcEncFile,
            File dstEncFile,
            File workDir,
            Map<String, Map<String, RowPatchValue>> fileTableRows,
            Map<String, String> dictFallback,
            boolean matchOriginalSize
    ) throws Exception {
        long origSize = srcEncFile.length();
        SingleDbPatchResult result = new SingleDbPatchResult();
        result.origSize = origSize;
        result.finalSize = origSize;

        if (!workDir.exists()) {
            workDir.mkdirs();
        }
        File tmpPlain = new File(workDir, "bd2_work_" + System.nanoTime() + ".db");
        File tmpEnc = new File(workDir, "bd2_enc_" + System.nanoTime() + ".bin");

        try {
            decryptFileStreaming(srcEncFile, tmpPlain);

            int patchedRows = 0;
            List<ModifiedPbRow> modifiedPbRows = new ArrayList<>();

            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    tmpPlain.getAbsolutePath(),
                    null,
                    SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS
            );
            try {
                execPragmaQuiet(db, "PRAGMA journal_mode = DELETE");
                execPragmaQuiet(db, "PRAGMA synchronous = OFF");
                execPragmaQuiet(db, "PRAGMA temp_store = MEMORY");

                List<String> allTables = listTables(db);
                Set<String> allLower = new HashSet<>();
                for (String t : allTables) {
                    allLower.add(t.toLowerCase(Locale.US));
                }

                // تحديد الجداول المستهدفة: إن وُجدت خريطة جداول دقيقة لهذه القاعدة نعالجها بسرعة فائقة، وإلا نمسح كل جداول النصوص بالقاموس الاحتياطي
                List<String> tablesToProcess = new ArrayList<>();
                boolean useFastTableFilter = fileTableRows != null && !fileTableRows.isEmpty();
                for (String t : allTables) {
                    if (!useFastTableFilter || fileTableRows.containsKey(t)) {
                        tablesToProcess.add(t);
                    }
                }

                db.beginTransaction();
                try {
                    for (String tbl : tablesToProcess) {
                        List<String> cols = listColumns(db, tbl);
                        TableSpec spec = getTableSpec(tbl, cols, allLower);
                        if (spec == null) {
                            continue;
                        }
                        boolean hasIdCol = cols.contains("id");
                        boolean hasPb = spec.pbFields.length > 0 && cols.contains("ProtoBuf");
                        List<String> selectCols = new ArrayList<>(spec.sqlCols);
                        if (hasPb) {
                            selectCols.add("ProtoBuf");
                        }
                        if (selectCols.isEmpty()) {
                            continue;
                        }

                        Map<String, RowPatchValue> idMap = (fileTableRows != null) ? fileTableRows.get(tbl) : null;
                        StringBuilder colSql = new StringBuilder();
                        StringBuilder setSql = new StringBuilder();
                        for (int i = 0; i < selectCols.size(); i++) {
                            if (i > 0) {
                                colSql.append(", ");
                                setSql.append(", ");
                            }
                            colSql.append('"').append(selectCols.get(i)).append('"');
                            setSql.append('"').append(selectCols.get(i)).append("\" = ?");
                        }

                        SQLiteStatement updateStmt = db.compileStatement(
                                "UPDATE \"" + tbl + "\" SET " + setSql + " WHERE rowid = ?"
                        );
                        try {
                            String tl = tbl.toLowerCase(Locale.US);
                            // المسار السريع بالمعرّفات (id IN (...)) لتسريع قاعدة 104 MB من ثوانٍ إلى أجزاء من الثانية
                            if (hasIdCol && idMap != null && !idMap.isEmpty()) {
                                List<String> targetIds = new ArrayList<>(idMap.keySet());
                                int batchSize = 400;
                                for (int off = 0; off < targetIds.size(); off += batchSize) {
                                    int end = Math.min(off + batchSize, targetIds.size());
                                    StringBuilder placeholders = new StringBuilder();
                                    String[] args = new String[end - off];
                                    for (int k = off; k < end; k++) {
                                        if (k > off) placeholders.append(',');
                                        placeholders.append('?');
                                        args[k - off] = targetIds.get(k);
                                    }
                                    String query = "SELECT rowid, \"id\", " + colSql + " FROM \"" + tbl + "\" WHERE \"id\" IN (" + placeholders + ")";
                                    patchedRows += patchCursorRows(
                                            db, query, args, tbl, tl, spec, hasPb, selectCols, idMap, dictFallback, updateStmt, modifiedPbRows
                                    );
                                }
                            } else {
                                // المسار الشامل بالقاموس الاحتياطي (OTA-Resilient Fallback)
                                String query = "SELECT rowid, " + (hasIdCol ? "\"id\"" : "rowid") + ", " + colSql + " FROM \"" + tbl + "\"";
                                patchedRows += patchCursorRows(
                                        db, query, null, tbl, tl, spec, hasPb, selectCols, idMap, dictFallback, updateStmt, modifiedPbRows
                                );
                            }
                        } finally {
                            updateStmt.close();
                        }
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } finally {
                db.close();
            }

            if (patchedRows == 0) {
                result.modified = false;
                result.patchedRows = 0;
                return result;
            }

            deleteSqliteAuxFiles(tmpPlain);

            // خطوة 1: VACUUM إذا تجاوز الحجم الأصلي
            long curSize = tmpPlain.length();
            if (matchOriginalSize && origSize > 0 && curSize > origSize) {
                runVacuum(tmpPlain);
                curSize = tmpPlain.length();
            }

            // خطوة 2: تقليص حقول Overflow غير الإنجليزية (TW ثم CN ثم JP) في الصفوف المعدّلة فقط
            if (matchOriginalSize && origSize > 0 && curSize > origSize && !modifiedPbRows.isEmpty()) {
                int[][] trimStages = new int[][]{{7}, {7, 4}, {7, 4, 6}};
                for (int[] stage : trimStages) {
                    SQLiteDatabase dbC = SQLiteDatabase.openDatabase(
                            tmpPlain.getAbsolutePath(), null,
                            SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS
                    );
                    try {
                        execPragmaQuiet(dbC, "PRAGMA journal_mode = DELETE");
                        execPragmaQuiet(dbC, "PRAGMA synchronous = OFF");
                        dbC.beginTransaction();
                        try {
                            Map<String, SQLiteStatement> stmts = new HashMap<>();
                            try {
                                for (ModifiedPbRow mr : modifiedPbRows) {
                                    int[] activeTrims = intersectInts(stage, mr.trimFields);
                                    if (activeTrims.length == 0) {
                                        continue;
                                    }
                                    PbPatchResult pr = patchProtobufFields(
                                            mr.origBlob, mr.pbFields, mr.fixedMap, dictFallback, activeTrims
                                    );
                                    SQLiteStatement st = stmts.get(mr.table);
                                    if (st == null) {
                                        st = dbC.compileStatement("UPDATE \"" + mr.table + "\" SET \"ProtoBuf\" = ? WHERE rowid = ?");
                                        stmts.put(mr.table, st);
                                    }
                                    st.clearBindings();
                                    st.bindBlob(1, pr.bytes);
                                    st.bindLong(2, mr.rowid);
                                    st.executeUpdateDelete();
                                }
                            } finally {
                                for (SQLiteStatement st : stmts.values()) {
                                    st.close();
                                }
                            }
                            dbC.setTransactionSuccessful();
                        } finally {
                            dbC.endTransaction();
                        }
                    } finally {
                        dbC.close();
                    }
                    runVacuum(tmpPlain);
                    curSize = tmpPlain.length();
                    if (curSize <= origSize) {
                        break;
                    }
                }
            }

            // خطوة 3: خطة طوارئ أخيرة لجداول *_EN المستقلة (تفريغ الجداول الموازية _TW/_CN/_JP)
            if (matchOriginalSize && origSize > 0 && curSize > origSize) {
                String[] siblingSufs = {"_TW", "_CN", "_JP"};
                for (String suf : siblingSufs) {
                    SQLiteDatabase dbF = SQLiteDatabase.openDatabase(
                            tmpPlain.getAbsolutePath(), null,
                            SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS
                    );
                    try {
                        execPragmaQuiet(dbF, "PRAGMA journal_mode = DELETE");
                        List<String> allT = listTables(dbF);
                        dbF.beginTransaction();
                        try {
                            for (String tn : allT) {
                                if (tn.endsWith(suf)) {
                                    List<String> tc = listColumns(dbF, tn);
                                    if (tc.contains("text")) {
                                        dbF.execSQL("UPDATE \"" + tn + "\" SET \"text\" = ''");
                                    }
                                    if (tc.contains("nodeAddText")) {
                                        dbF.execSQL("UPDATE \"" + tn + "\" SET \"nodeAddText\" = ''");
                                    }
                                    if (tc.contains("ProtoBuf")) {
                                        dbF.execSQL("UPDATE \"" + tn + "\" SET \"ProtoBuf\" = X''");
                                    }
                                }
                            }
                            dbF.setTransactionSuccessful();
                        } finally {
                            dbF.endTransaction();
                        }
                    } finally {
                        dbF.close();
                    }
                    runVacuum(tmpPlain);
                    curSize = tmpPlain.length();
                    if (curSize <= origSize) {
                        break;
                    }
                }
            }

            // خطوة 4: حشو صفري لمضاعفات 4096 ثم مطابقة الحجم الأصلي بايت ببايت (100%)
            padFileToExactSize(tmpPlain, matchOriginalSize ? origSize : 0L);

            // خطوة 5: إعادة التشفير بالتدفّق + التحقق الإلزامي من سلامة SQLite
            encryptFileStreaming(tmpPlain, tmpEnc);
            if (!verifyRoundTripIntegrity(tmpEnc, workDir, matchOriginalSize ? origSize : 0L)) {
                throw new IOException("فشل فحص السلامة Round-Trip لقاعدة البيانات: " + srcEncFile.getName());
            }

            // نقل ذري للملف النهائي
            ShellBridgeServer.copyFileAtomically(tmpEnc, dstEncFile);
            result.modified = true;
            result.patchedRows = patchedRows;
            result.finalSize = dstEncFile.length();
            return result;
        } finally {
            deleteSqliteAuxFiles(tmpPlain);
            tmpPlain.delete();
            tmpEnc.delete();
        }
    }

    private static int patchCursorRows(
            SQLiteDatabase db,
            String query,
            String[] args,
            String tbl,
            String tl,
            TableSpec spec,
            boolean hasPb,
            List<String> selectCols,
            Map<String, RowPatchValue> idMap,
            Map<String, String> dictFallback,
            SQLiteStatement updateStmt,
            List<ModifiedPbRow> modifiedPbRows
    ) {
        int hitsHere = 0;
        int sqlColCount = spec.sqlCols.size();
        try (Cursor c = db.rawQuery(query, args)) {
            while (c.moveToNext()) {
                long rowid = c.getLong(0);
                String rowIdKey = c.getString(1);
                RowPatchValue rpv = (idMap != null && rowIdKey != null) ? idMap.get(rowIdKey) : null;

                String[] newStrVals = new String[sqlColCount];
                boolean[] isNullStr = new boolean[sqlColCount];
                boolean rowChanged = false;
                int rowHits = 0;

                for (int i = 0; i < sqlColCount; i++) {
                    int colIdx = 2 + i;
                    if (c.isNull(colIdx)) {
                        isNullStr[i] = true;
                        continue;
                    }
                    String cur = c.getString(colIdx);
                    newStrVals[i] = cur;
                    if (cur == null || cur.isEmpty()) {
                        continue;
                    }
                    String colName = spec.sqlCols.get(i);
                    String rep = null;
                    if (rpv != null) {
                        if ("skilltexttable_en".equals(tl)) {
                            rep = "nodeAddText".equals(colName) ? rpv.nodeVal : rpv.textVal;
                            if (rep == null) rep = rpv.single;
                        } else {
                            rep = rpv.single != null ? rpv.single : rpv.textVal;
                        }
                    }
                    if (rep == null && dictFallback != null && !dictFallback.isEmpty()) {
                        rep = dictFallback.get(cur);
                        if (rep == null) rep = dictFallback.get(cur.trim());
                    }
                    if (rep != null && !rep.isEmpty() && !rep.equals(cur)) {
                        newStrVals[i] = rep;
                        rowChanged = true;
                        rowHits++;
                    }
                }

                byte[] newBlob = null;
                boolean isNullBlob = false;
                if (hasPb) {
                    int pbColIdx = 2 + sqlColCount;
                    if (c.isNull(pbColIdx)) {
                        isNullBlob = true;
                    } else {
                        byte[] origBlob = c.getBlob(pbColIdx);
                        newBlob = origBlob;
                        if (origBlob != null && origBlob.length > 0) {
                            Map<Integer, String> fixedMap = null;
                            if (rpv != null) {
                                fixedMap = new HashMap<>(4);
                                if ("skilltexttable_en".equals(tl)) {
                                    if (rpv.textVal != null) fixedMap.put(3, rpv.textVal);
                                    if (rpv.nodeVal != null) fixedMap.put(5, rpv.nodeVal);
                                    if (rpv.single != null) fixedMap.put(3, rpv.single);
                                } else if ("monsterhuntchangetexttable_en".equals(tl)) {
                                    String v = rpv.single != null ? rpv.single : rpv.textVal;
                                    if (v != null) fixedMap.put(3, v);
                                } else if (spec.pbFields.length == 1) {
                                    String v = rpv.single != null ? rpv.single : rpv.textVal;
                                    if (v != null) fixedMap.put(spec.pbFields[0], v);
                                }
                            }
                            PbPatchResult pr = patchProtobufFields(origBlob, spec.pbFields, fixedMap, dictFallback, null);
                            if (pr.hits > 0 && pr.changed) {
                                newBlob = pr.bytes;
                                if (sqlColCount == 0) {
                                    rowHits += pr.hits;
                                }
                                rowChanged = true;
                                if (spec.trimOverflowFields.length > 0 && newBlob.length > origBlob.length) {
                                    modifiedPbRows.add(new ModifiedPbRow(
                                            tbl, rowid, origBlob, spec.pbFields, spec.trimOverflowFields, fixedMap
                                    ));
                                }
                            }
                        }
                    }
                }

                if (rowChanged) {
                    updateStmt.clearBindings();
                    int bindPos = 1;
                    for (int i = 0; i < sqlColCount; i++) {
                        if (isNullStr[i] || newStrVals[i] == null) {
                            updateStmt.bindNull(bindPos++);
                        } else {
                            updateStmt.bindString(bindPos++, newStrVals[i]);
                        }
                    }
                    if (hasPb) {
                        if (isNullBlob || newBlob == null) {
                            updateStmt.bindNull(bindPos++);
                        } else {
                            updateStmt.bindBlob(bindPos++, newBlob);
                        }
                    }
                    updateStmt.bindLong(bindPos, rowid);
                    updateStmt.executeUpdateDelete();
                    hitsHere += Math.max(1, rowHits);
                }
            }
        }
        return hitsHere;
    }

    private static int[] intersectInts(int[] a, int[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return new int[0];
        }
        int[] tmp = new int[Math.min(a.length, b.length)];
        int count = 0;
        for (int x : a) {
            if (containsInt(b, x)) {
                tmp[count++] = x;
            }
        }
        return Arrays.copyOf(tmp, count);
    }

    private static void runVacuum(File plainDb) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                plainDb.getAbsolutePath(), null,
                SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS
        );
        try {
            execPragmaQuiet(db, "PRAGMA journal_mode = DELETE");
            db.execSQL("VACUUM");
        } finally {
            db.close();
        }
        deleteSqliteAuxFiles(plainDb);
    }

    private static void padFileToExactSize(File plainDb, long origSize) throws IOException {
        long cur = plainDb.length();
        long target = cur;
        long rem = target % PAGE_SIZE;
        if (rem != 0) {
            target += (PAGE_SIZE - rem);
        }
        if (origSize > 0 && target < origSize) {
            target = origSize;
        }
        long padBytes = target - cur;
        if (padBytes <= 0) {
            return;
        }
        byte[] zeros = new byte[PAGE_SIZE];
        try (FileOutputStream fos = new FileOutputStream(plainDb, true)) {
            long left = padBytes;
            while (left > 0) {
                int w = (int) Math.min(zeros.length, left);
                fos.write(zeros, 0, w);
                left -= w;
            }
            fos.flush();
        }
    }

    private static boolean verifyRoundTripIntegrity(File encFile, File workDir, long expectedSize) {
        if (!encFile.isFile()) {
            return false;
        }
        long sz = encFile.length();
        if (sz < PAGE_SIZE || (sz % PAGE_SIZE) != 0) {
            return false;
        }
        if (expectedSize > 0 && sz != expectedSize) {
            return false;
        }
        if (!isEncryptedBd2SqliteFile(encFile)) {
            return false;
        }
        File chkPlain = new File(workDir, "bd2_rt_" + System.nanoTime() + ".db");
        try {
            decryptFileStreaming(encFile, chkPlain);
            SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    chkPlain.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS
            );
            try (Cursor c = db.rawQuery("PRAGMA quick_check", null)) {
                return c.moveToFirst() && "ok".equalsIgnoreCase(c.getString(0));
            } finally {
                db.close();
            }
        } catch (Throwable t) {
            return false;
        } finally {
            deleteSqliteAuxFiles(chkPlain);
            chkPlain.delete();
        }
    }

    private static File resolveFastWorkDir(File fallbackParent) {
        File tmpShell = new File("/data/local/tmp/rdav_bd2_work");
        if (canUseWorkDir(tmpShell)) {
            return tmpShell;
        }
        File fb = new File(fallbackParent, ".bd2_tmp_work");
        fb.mkdirs();
        return fb;
    }

    private static boolean canUseWorkDir(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File probe = new File(dir, ".p_" + System.nanoTime());
            try (FileOutputStream fos = new FileOutputStream(probe)) {
                fos.write(1);
            }
            boolean ok = probe.isFile();
            probe.delete();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    // =========================================================================
    // 5) تطبيق الحزمة الخفيفة عبر Shizuku / DirectFS (ShellBridgeServer)
    // =========================================================================

    static LightPatchResult applyLightPatchDirect(
            File filesDir,
            File origDir,
            ShellBridgeServer.BackupManifest prevManifest,
            ShellBridgeServer.BackupManifest nextManifest,
            File patchJsonFile,
            ShellBridgeServer.ProgressListener listener
    ) throws Exception {
        if (listener != null) {
            listener.onProgress(62, "جارٍ قراءة قاموس BrownDust II الخفيف...");
        }
        LightPatchPayload payload = parsePatchJson(patchJsonFile);
        if (payload.files.isEmpty() && payload.dict.isEmpty()) {
            throw new IllegalStateException("ملف قاموس BrownDust II فارغ.");
        }

        // تحديد قائمة الملفات المستهدفة على الجهاز
        LinkedHashMap<String, File> targets = new LinkedHashMap<>();
        int missingMappedFiles = 0;

        for (String rel : payload.files.keySet()) {
            File f = new File(filesDir, rel);
            if (isEncryptedBd2SqliteFile(f)) {
                targets.put(rel, f);
            } else {
                missingMappedFiles++;
            }
        }

        // إذا تغيّرت أسماء بعض الملفات بسبب تحديث OTA أو كان القاموس مسطّحاً -> مسح كل Data/ و Data/t/ بالقاموس الاحتياطي
        if ((payload.files.isEmpty() || missingMappedFiles > 0) && !payload.dict.isEmpty()) {
            File dataDir = new File(filesDir, "Data");
            if (dataDir.isDirectory()) {
                List<File> allDataFiles = new ArrayList<>();
                ShellBridgeServer.collectFilesRecursively(dataDir, allDataFiles);
                Collections.sort(allDataFiles, (a, b) -> a.getAbsolutePath().compareTo(b.getAbsolutePath()));
                String basePath = filesDir.getCanonicalPath() + File.separator;
                for (File df : allDataFiles) {
                    String canon = df.getCanonicalPath();
                    if (!canon.startsWith(basePath)) {
                        continue;
                    }
                    String rel = canon.substring(basePath.length()).replace('\\', '/');
                    if (!targets.containsKey(rel) && isEncryptedBd2SqliteFile(df)) {
                        targets.put(rel, df);
                    }
                }
            }
        }

        if (targets.isEmpty()) {
            throw new IllegalStateException("لم يتم العثور على قواعد بيانات BrownDust II المشفّرة في مجلد Data/ على الجهاز. يرجى تشغيل اللعبة مرة واحدة لإكمال تحميل البيانات.");
        }

        File workDir = resolveFastWorkDir(origDir.getParentFile() != null ? origDir.getParentFile() : filesDir);
        LightPatchResult out = new LightPatchResult();
        List<Map.Entry<String, File>> list = new ArrayList<>(targets.entrySet());
        int total = list.size();

        try {
            for (int i = 0; i < total; i++) {
                String rel = list.get(i).getKey();
                File targetFile = list.get(i).getValue();
                File backupFile = new File(origDir, rel);
                long origMtime = targetFile.lastModified();

                // إدارة النسخة الاحتياطية الذكية (Clean Original Source + OTA Fresh Backup)
                ShellBridgeServer.BackupEntry prev = prevManifest.byPath.get(rel);
                File cleanSource;
                if (prev != null && prev.hadOriginal && backupFile.isFile()) {
                    boolean changedFromPatched = targetFile.length() != prev.patchedSize
                            || (!prev.patchedSha256.isEmpty() && !prev.patchedSha256.equalsIgnoreCase(ShellBridgeServer.sha256Lower(targetFile)));
                    if (changedFromPatched) {
                        boolean differsFromBackup = targetFile.length() != backupFile.length()
                                || !ShellBridgeServer.sha256Lower(targetFile).equalsIgnoreCase(ShellBridgeServer.sha256Lower(backupFile));
                        if (differsFromBackup) {
                            // تحديث OTA جديد من اللعبة -> حدّث النسخة الأصلية النظيفة أولاً!
                            ShellBridgeServer.copyFileAtomically(targetFile, backupFile);
                        }
                    }
                    cleanSource = backupFile;
                } else {
                    if (!backupFile.isFile()) {
                        ShellBridgeServer.copyFileAtomically(targetFile, backupFile);
                    }
                    cleanSource = backupFile.isFile() ? backupFile : targetFile;
                }

                Map<String, Map<String, RowPatchValue>> tableMap = payload.files.get(rel);
                try {
                    SingleDbPatchResult r = patchSingleEncryptedDb(
                            cleanSource,
                            targetFile,
                            workDir,
                            tableMap,
                            payload.dict,
                            payload.matchOriginalSize
                    );
                    if (r.modified) {
                        if (origMtime > 0L) {
                            try {
                                targetFile.setLastModified(origMtime);
                            } catch (Throwable ignored) {
                            }
                        }
                        String finalSha = ShellBridgeServer.sha256Lower(targetFile);
                        ShellBridgeServer.BackupEntry newEntry =
                                new ShellBridgeServer.BackupEntry(rel, targetFile.length(), true, finalSha);
                        nextManifest.entries.add(newEntry);
                        nextManifest.byPath.put(rel, newEntry);
                        out.patchedFiles++;
                        out.patchedRows += r.patchedRows;
                    } else {
                        out.skippedFiles++;
                    }
                } catch (Throwable dbErr) {
                    out.failedFiles++;
                    // في حال فشل قاعدة مفردة، نضمن بقاء الملف الأصلي سليماً دون إتلافه
                    if (backupFile.isFile() && (!targetFile.isFile() || targetFile.length() != backupFile.length())) {
                        try {
                            ShellBridgeServer.copyFileAtomically(backupFile, targetFile);
                        } catch (Throwable ignored) {
                        }
                    }
                }

                if (listener != null && (i % 3 == 0 || i == total - 1)) {
                    int pct = 64 + ((i + 1) * 32) / total;
                    listener.onProgress(pct, "جارٍ فك التشفير والحقن المحلي (" + (i + 1) + "/" + total + " قاعدة • " + out.patchedRows + " صف)...");
                }
            }
        } finally {
            ShellBridgeServer.deleteRecursive(workDir);
        }

        if (out.patchedFiles == 0) {
            throw new IllegalStateException("لم يتم حقن أي قاعدة بيانات (" + out.failedFiles + " أخطاء، " + out.skippedFiles + " متخطاة).");
        }
        return out;
    }

    // =========================================================================
    // 6) تطبيق الحزمة الخفيفة عبر SAF (SafHelper)
    // =========================================================================

    static LightPatchResult applyLightPatchSaf(
            Context context,
            Uri treeUri,
            String filesDocId,
            String origDirId,
            ShellBridgeServer.BackupManifest prevManifest,
            ShellBridgeServer.BackupManifest nextManifest,
            File patchJsonFile,
            ShellBridgeServer.ProgressListener listener
    ) throws Exception {
        if (listener != null) {
            listener.onProgress(62, "جارٍ قراءة قاموس BrownDust II الخفيف (SAF)...");
        }
        LightPatchPayload payload = parsePatchJson(patchJsonFile);
        if (payload.files.isEmpty()) {
            throw new IllegalStateException("ملف قاموس BrownDust II لا يحتوي خريطة ملفات لوضع SAF.");
        }

        File workDir = new File(context.getCacheDir(), "rdav_bd2_saf_" + System.nanoTime());
        workDir.mkdirs();
        LightPatchResult out = new LightPatchResult();
        List<String> relList = new ArrayList<>(payload.files.keySet());
        int total = relList.size();

        try {
            for (int i = 0; i < total; i++) {
                String rel = relList.get(i);
                String targetDocId = filesDocId + "/" + rel;
                String backupDocId = origDirId + "/" + rel;
                if (!SafHelper.docExists(context, treeUri, targetDocId)) {
                    out.skippedFiles++;
                    continue;
                }

                ShellBridgeServer.BackupEntry prev = prevManifest.byPath.get(rel);
                boolean backupExists = SafHelper.docExists(context, treeUri, backupDocId);
                if (prev != null && prev.hadOriginal && backupExists) {
                    long tLen = SafHelper.docLength(context, treeUri, targetDocId);
                    long bLen = SafHelper.docLength(context, treeUri, backupDocId);
                    if (tLen != prev.patchedSize && tLen != bLen) {
                        SafHelper.copySafToSaf(context, treeUri, targetDocId, backupDocId);
                    }
                } else if (!backupExists) {
                    SafHelper.copySafToSaf(context, treeUri, targetDocId, backupDocId);
                    backupExists = true;
                }

                File localSrc = new File(workDir, "src_" + i + ".bin");
                File localDst = new File(workDir, "dst_" + i + ".bin");
                try {
                    SafHelper.copySafToLocalFile(context, treeUri, backupExists ? backupDocId : targetDocId, localSrc);
                    if (!isEncryptedBd2SqliteFile(localSrc)) {
                        out.skippedFiles++;
                        continue;
                    }
                    SingleDbPatchResult r = patchSingleEncryptedDb(
                            localSrc,
                            localDst,
                            workDir,
                            payload.files.get(rel),
                            payload.dict,
                            payload.matchOriginalSize
                    );
                    if (r.modified && localDst.isFile()) {
                        SafHelper.copyLocalFileToSaf(context, treeUri, localDst, targetDocId);
                        String finalSha = ShellBridgeServer.sha256Lower(localDst);
                        ShellBridgeServer.BackupEntry newEntry =
                                new ShellBridgeServer.BackupEntry(rel, localDst.length(), true, finalSha);
                        nextManifest.entries.add(newEntry);
                        nextManifest.byPath.put(rel, newEntry);
                        out.patchedFiles++;
                        out.patchedRows += r.patchedRows;
                    } else {
                        out.skippedFiles++;
                    }
                } catch (Throwable dbErr) {
                    out.failedFiles++;
                } finally {
                    localSrc.delete();
                    localDst.delete();
                }

                if (listener != null && (i % 3 == 0 || i == total - 1)) {
                    int pct = 64 + ((i + 1) * 32) / total;
                    listener.onProgress(pct, "جارٍ الحقن المحلي عبر SAF (" + (i + 1) + "/" + total + " قاعدة • " + out.patchedRows + " صف)...");
                }
            }
        } finally {
            ShellBridgeServer.deleteRecursive(workDir);
        }

        if (out.patchedFiles == 0) {
            throw new IllegalStateException("لم يتم حقن أي قاعدة بيانات عبر SAF (" + out.failedFiles + " أخطاء).");
        }
        return out;
    }
}
