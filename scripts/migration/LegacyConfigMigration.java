import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.Yaml;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Explicit one-off configuration import. Source is read-only; target uses one transaction. */
class LegacyConfigMigration {
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> TABLES = List.of("project", "model_config", "prompt", "prompt_version", "review_strategy");
    static final String DATABASE = "ai_code_review_agent";
    static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    static final TypeReference<LinkedHashMap<String,Object>> MAP_TYPE = new TypeReference<>() {};
    static String stage = "arguments";

    public static void main(String[] args) {
        try {
            if (args.length == 1 && args[0].equals("self-test")) { selfTest(); return; }
            if (args.length != 4 || !List.of("plan", "apply", "verify").contains(args[0])) throw new IllegalArgumentException();
            Path sourcePath = Path.of(args[1]).toAbsolutePath().normalize();
            Path targetPath = Path.of(args[2]).toAbsolutePath().normalize();
            Path run = Path.of(args[3]).toAbsolutePath().normalize();
            Path backend = targetPath.getParent().getParent().getParent().getParent();
            List<Path> allowedRoots = List.of(backend.resolve(".local-backups/config-migration"), backend.resolve("target/config-migration"));
            if (allowedRoots.stream().noneMatch(allowed -> run.startsWith(allowed) && !run.equals(allowed))) throw new IllegalArgumentException("Unsafe artifact directory");
            Map<String,Object> sourceConfig = config(sourcePath), targetConfig = config(targetPath);
            Map<String,Object> sourceData = child(child(sourceConfig, "spring"), "datasource");
            Map<String,Object> targetData = child(child(targetConfig, "agent"), "datasource");
            String zone = sourceZone(sourceConfig, sourceData);
            try (Connection source = connect(sourceData); Connection target = connect(targetData)) {
                if (!"code_review".equals(source.getCatalog()) || !DATABASE.equals(target.getCatalog())) throw new IllegalStateException("Unexpected database identity");
                if (!"1".equals(scalar(target, "select schema_version from agent_schema_identity where schema_name='ai_code_review_agent'"))) throw new IllegalStateException("Unexpected Agent schema identity");
                source.setReadOnly(true);
                source.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                source.setAutoCommit(false);
                if (args[0].equals("plan")) plan(source, target, run, ZoneId.of(zone));
                else if (args[0].equals("apply")) apply(source, target, run);
                else verify(target, run);
                source.rollback();
            }
        } catch (SQLException e) {
            // SQL errors can contain statement values: print only non-secret diagnostics.
            System.err.println("FAILED stage=" + stage + " SQLState=" + e.getSQLState() + " code=" + e.getErrorCode()); System.exit(1);
        } catch (Exception e) {
            System.err.println("FAILED stage=" + stage + " type=" + e.getClass().getSimpleName()); System.exit(1);
        }
    }

    @SuppressWarnings("unchecked") static Map<String,Object> child(Map<String,Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Missing configuration group");
        return (Map<String,Object>) value;
    }
    static Map<String,Object> config(Path file) throws Exception { return new Yaml().load(Files.readString(file)); }
    static String env(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = System.getenv(m.group(1));
            if (replacement == null) replacement = m.group(2);
            if (replacement == null) throw new IllegalArgumentException("Missing environment value");
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out); return out.toString();
    }
    static String value(Map<String,Object> row, String key) { Object v = row.get(key); return v == null ? null : v.toString(); }
    static Connection connect(Map<String,Object> config) throws Exception {
        Properties p = new Properties();
        p.setProperty("user", env(value(config, "username")));
        p.setProperty("password", env(value(config, "password")));
        p.setProperty("connectTimeout", "8000"); p.setProperty("socketTimeout", "30000");
        return DriverManager.getConnection(env(value(config, "url")), p);
    }
    static String sourceZone(Map<String,Object> config, Map<String,Object> datasource) throws Exception {
        Matcher m = Pattern.compile("[?&](?:serverTimezone|connectionTimeZone)=([^&]+)").matcher(env(value(datasource, "url")));
        if (m.find()) return java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        return value(child(child(config, "spring"), "jackson"), "time-zone");
    }
    static String scalar(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            if (!r.next()) throw new IllegalStateException("Missing result"); return r.getString(1);
        }
    }
    static List<Map<String,Object>> query(Connection c, String sql) throws Exception {
        List<Map<String,Object>> result = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            ResultSetMetaData metadata = r.getMetaData();
            while (r.next()) {
                Map<String,Object> row = new LinkedHashMap<>();
                for (int i=1; i<=metadata.getColumnCount(); i++) {
                    String name = metadata.getColumnLabel(i), text = r.getString(i);
                    if (text != null && (name.equals("created_at") || name.equals("updated_at"))) text = LocalDateTime.parse(text.replace(' ', 'T')).format(SQL_TIME);
                    row.put(name, text);
                }
                result.add(row);
            }
        }
        return result;
    }
    static Map<String,List<Map<String,Object>>> sourceRows(Connection source) throws Exception {
        Map<String,List<Map<String,Object>>> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            String sql = table.equals("prompt_version")
                ? "select v.* from prompt_version v join prompt p on p.id=v.prompt_id where v.is_deleted=0 and p.is_deleted=0 order by v.id"
                : "select * from `" + table + "` where is_deleted=0 order by id";
            rows.put(table, query(source, sql));
        }
        return rows;
    }
    static Map<String,List<Map<String,Object>>> targetRows(Connection target, Map<String,List<Map<String,Object>>> expected, boolean lock) throws Exception {
        Map<String,List<Map<String,Object>>> rows = new LinkedHashMap<>();
        for (String table : TABLES) {
            List<Map<String,Object>> sample = expected.get(table);
            if (sample.isEmpty()) throw new IllegalStateException("Empty migration category requires explicit handling");
            String columns = String.join(",", sample.get(0).keySet().stream().map(k -> "`" + k + "`").toList());
            rows.put(table, query(target, "select " + columns + " from `" + table + "` order by id" + (lock ? " for update" : "")));
        }
        return rows;
    }
    static String hash(Object object) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(object))); }
    static void write(Path path, Object object) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(object) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    static String utc(String date, ZoneId sourceZone) {
        if (date == null) throw new IllegalArgumentException("Missing source timestamp");
        return LocalDateTime.parse(date.replace(' ', 'T')).atZone(sourceZone).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().format(SQL_TIME);
    }
    static String id(String table, String oldId, String createdAt, ZoneId zone) throws Exception {
        long millis = LocalDateTime.parse(createdAt.replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(("legacy-config:code_review:" + table + ":" + oldId).getBytes(StandardCharsets.UTF_8));
        BigInteger number = BigInteger.valueOf(millis).shiftLeft(80).or(new BigInteger(1, Arrays.copyOf(digest, 10)));
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        char[] result = new char[26];
        for (int i=25; i>=0; i--) { result[i] = alphabet.charAt(number.and(BigInteger.valueOf(31)).intValue()); number = number.shiftRight(5); }
        return new String(result);
    }
    static String reference(Map<String,String> ids, String oldId) {
        String result = ids.get(oldId);
        if (result == null) throw new IllegalArgumentException("Missing active reference");
        return result;
    }
    static String type(String old) {
        return switch (old) { case "1", "5" -> "ISSUE"; case "2" -> "COUPLING"; case "3" -> "PATTERN"; default -> throw new IllegalArgumentException("Unsupported analyzer type"); };
    }
    static String tags(String old) throws Exception {
        if (old == null || old.isBlank()) return null;
        JsonNode array = JSON.readTree(old);
        if (!array.isArray()) throw new IllegalArgumentException("Invalid tag list");
        Set<String> items = new LinkedHashSet<>();
        for (JsonNode item : array) {
            if (!item.isTextual() || item.asText().contains(",") || item.asText().length()>50) throw new IllegalArgumentException("Tag cannot be represented losslessly");
            if (!item.asText().isBlank()) items.add(item.asText().trim());
        }
        return items.isEmpty() ? null : String.join(",", items);
    }
    static String positive(JsonNode object, String name) {
        JsonNode n = object.get(name);
        if (n == null || n.isNull()) return null;
        if (!n.isIntegralNumber() || !n.canConvertToInt() || n.intValue()<=0) throw new IllegalArgumentException("Invalid declared capacity");
        return n.asText();
    }
    static void length(String text, int max, boolean required) {
        if ((required && (text == null || text.isBlank())) || (text != null && text.codePointCount(0, text.length())>max)) throw new IllegalArgumentException("Field does not fit target constraints");
    }
    static Map<String,Object> common(String table, Map<String,Object> old, Map<String,Map<String,String>> ids, ZoneId zone) {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("id", reference(ids.get(table), value(old,"id")));
        row.put("name", value(old,"name"));
        length(value(old,"name"),100,true);
        row.put("revision","0"); row.put("created_at",utc(value(old,"created_at"),zone)); row.put("updated_at",utc(value(old,"updated_at"),zone));
        row.put("created_by",value(old,"created_by")); row.put("updated_by",value(old,"updated_by")); row.put("is_deleted","0");
        return row;
    }
    static void plan(Connection source, Connection target, Path run, ZoneId zone) throws Exception {
        stage = "read-source-snapshot";
        if (Files.exists(run)) throw new IllegalStateException("Run directory already exists");
        Map<String,List<Map<String,Object>>> old = sourceRows(source), converted = new LinkedHashMap<>();
        Map<String,Map<String,String>> ids = new LinkedHashMap<>();
        List<Map<String,Object>> audit = new ArrayList<>();
        for (String table : TABLES) {
            ids.put(table,new LinkedHashMap<>()); converted.put(table,new ArrayList<>());
            for (Map<String,Object> row : old.get(table)) ids.get(table).put(value(row,"id"),id(table,value(row,"id"),value(row,"created_at"),zone));
        }
        Set<String> referencedPrompts = new HashSet<>();
        for (Map<String,Object> row : old.get("review_strategy")) {
            JsonNode params = JSON.readTree(Optional.ofNullable(value(row,"params_json")).orElse("{}"));
            String prompt = params.path("promptId").asText("");
            if (prompt.isBlank()) prompt = params.path("promptVersionId").asText("");
            if (!prompt.isBlank()) { reference(ids.get("prompt"),prompt); referencedPrompts.add(prompt); }
        }
        stage = "convert-and-check-references";
        for (String table : TABLES) for (Map<String,Object> row : old.get(table)) {
            Map<String,Object> out;
            Map<String,Object> note = new LinkedHashMap<>();
            note.put("table",table); note.put("oldId",value(row,"id")); note.put("newId",ids.get(table).get(value(row,"id")));
            if (!table.equals("prompt_version")) note.put("name",value(row,"name"));
            if (table.equals("prompt_version")) {
                out = new LinkedHashMap<>(); out.put("id",reference(ids.get(table),value(row,"id")));
                out.put("prompt_id",reference(ids.get("prompt"),value(row,"prompt_id")));
                out.put("version_no",value(row,"version_no")); out.put("content",value(row,"content")); out.put("created_at",utc(value(row,"created_at"),zone));
                length(value(row,"content"),Integer.MAX_VALUE,true);
                note.put("promptOldId",value(row,"prompt_id")); note.put("versionNo",value(row,"version_no")); note.put("contentSha256",hash(value(row,"content")));
            } else {
                out = common(table,row,ids,zone);
                switch (table) {
                    case "project" -> {
                        out.put("repo_url",value(row,"gitea_url")); out.put("credential",value(row,"credential")); out.put("credential_type",value(row,"credential_type"));
                        out.put("current_branch",value(row,"current_branch") == null || value(row,"current_branch").isBlank() ? "main" : value(row,"current_branch"));
                        length(value(out,"repo_url"),500,true); length(value(out,"credential"),1024,false); length(value(out,"current_branch"),200,true);
                    }
                    case "model_config" -> {
                        out.put("base_url",value(row,"base_url")); out.put("model_name",value(row,"model_name")); out.put("credential",value(row,"token"));
                        JsonNode profile = JSON.readTree(Optional.ofNullable(value(row,"capability_profile_json")).orElse("{}"));
                        out.put("context_window",positive(profile,"contextWindowTokens")); out.put("max_output",positive(profile,"maxOutputTokens"));
                        out.put("tokenizer",null); out.put("protocol","OPENAI_CHAT");
                        length(value(out,"base_url"),500,true); length(value(out,"model_name"),200,true); length(value(out,"credential"),1024,false);
                        note.put("capacityDeclared",out.get("context_window") != null); note.put("validationStatus","NONE"); note.put("oldOutputParameter",value(row,"output_parameter"));
                    }
                    case "prompt" -> {
                        String oldId = value(row,"id"), current = value(row,"current_version_id");
                        out.put("description",value(row,"description")); out.put("tags",tags(value(row,"tags")));
                        boolean report = !referencedPrompts.contains(oldId) && (value(row,"name").contains("报告") || Optional.ofNullable(value(out,"tags")).orElse("").contains("报告"));
                        out.put("purpose",report ? "REPORT" : "SPECIALIST");
                        out.put("current_version_id",reference(ids.get("prompt_version"),current));
                        Map<String,Object> currentRow = old.get("prompt_version").stream().filter(v -> current.equals(value(v,"id"))).findFirst().orElseThrow();
                        if (!oldId.equals(value(currentRow,"prompt_id"))) throw new IllegalStateException("Current version belongs to another prompt");
                        length(value(out,"description"),500,false); length(value(out,"tags"),500,false);
                        note.put("purpose",out.get("purpose")); note.put("oldCurrentVersionId",current);
                    }
                    case "review_strategy" -> {
                        JsonNode params = JSON.readTree(Optional.ofNullable(value(row,"params_json")).orElse("{}"));
                        out.put("specialist_type",type(value(row,"analyzer_type")));
                        out.put("model_config_id",reference(ids.get("model_config"),params.path("modelConfigId").asText()));
                        String prompt = params.path("promptId").asText(""); if (prompt.isBlank()) prompt = params.path("promptVersionId").asText("");
                        out.put("prompt_id",prompt.isBlank() ? null : reference(ids.get("prompt"),prompt));
                        note.put("oldAnalyzerType",value(row,"analyzer_type")); note.put("specialistType",out.get("specialist_type"));
                        note.put("modelId",out.get("model_config_id")); note.put("promptId",out.get("prompt_id"));
                        List<String> omitted = new ArrayList<>(); params.fieldNames().forEachRemaining(k -> { if (!List.of("modelConfigId","promptId","promptVersionId").contains(k)) omitted.add(k); });
                        note.put("legacyOnlyParameterKeys",omitted);
                    }
                    default -> throw new IllegalArgumentException();
                }
            }
            converted.get(table).add(out); audit.add(note);
        }
        for (List<Map<String,Object>> rows : converted.values()) rows.sort(Comparator.comparing(r -> value(r,"id")));
        Map<String,List<Map<String,Object>>> before = targetRows(target,converted,false);
        if (before.values().stream().anyMatch(rows -> !rows.isEmpty())) throw new IllegalStateException("Target configuration is not empty; refusing to overwrite");
        Files.createDirectories(run);
        Map<String,Object> packet = new LinkedHashMap<>();
        packet.put("sourceFingerprint",hash(old)); packet.put("sourceTimeZone",zone.toString()); packet.put("targetRows",converted); packet.put("targetBefore",before);
        write(run.resolve("source-snapshot.json"),old); write(run.resolve("target-before.json"),before); write(run.resolve("plan.json"),packet);
        Map<String,Object> manifest = new LinkedHashMap<>(); manifest.put("sourceDatabase","code_review"); manifest.put("targetDatabase",DATABASE); manifest.put("sourceTimeZone",zone.toString()); manifest.put("sourceFingerprint",hash(old)); manifest.put("records",audit);
        write(run.resolve("id-map.json"),manifest);
        for (String table : TABLES) System.out.println("PLAN " + table + "=" + converted.get(table).size());
        System.out.println("PLAN ready; snapshots contain credentials and must remain in the ignored run directory.");
    }
    @SuppressWarnings("unchecked") static Map<String,List<Map<String,Object>>> rows(Map<String,Object> plan, String key) { return (Map<String,List<Map<String,Object>>>)plan.get(key); }
    static Map<String,Object> packet(Path run) throws Exception { return JSON.readValue(Files.readString(run.resolve("plan.json")), MAP_TYPE); }
    static boolean equalRows(Map<String,List<Map<String,Object>>> a, Map<String,List<Map<String,Object>>> b) { return a.equals(b); }
    static void apply(Connection source, Connection target, Path run) throws Exception {
        stage = "apply-source-fingerprint";
        Map<String,Object> packet = packet(run);
        if (!Objects.equals(packet.get("sourceFingerprint"),hash(sourceRows(source)))) throw new IllegalStateException("Source changed since plan");
        Map<String,List<Map<String,Object>>> expected = rows(packet,"targetRows");
        target.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE); target.setAutoCommit(false);
        try {
            stage = "apply-target-snapshot";
            Map<String,List<Map<String,Object>>> actual = targetRows(target,expected,true);
            if (equalRows(actual,expected)) { target.rollback(); System.out.println("APPLY already applied; no rows changed."); return; }
            if (!equalRows(actual,rows(packet,"targetBefore"))) throw new IllegalStateException("Target changed since plan");
            stage = "apply-transaction";
            for (String table : TABLES) for (Map<String,Object> row : expected.get(table)) {
                String columns = String.join(",",row.keySet().stream().map(k -> "`" + k + "`").toList());
                String marks = String.join(",",Collections.nCopies(row.size(),"?"));
                try (PreparedStatement statement = target.prepareStatement("insert into `" + table + "` (" + columns + ") values (" + marks + ")")) {
                    int index=1; for (Object v : row.values()) statement.setString(index++,v == null ? null : v.toString());
                    if (statement.executeUpdate()!=1) throw new IllegalStateException("Unexpected insert result");
                }
            }
            stage = "verify-before-commit";
            requireEqual(target,expected); target.commit();
            write(run.resolve("applied.json"),Map.of("committedAt",Instant.now().toString(),"sourceFingerprint",packet.get("sourceFingerprint")));
            System.out.println("APPLY committed; source remains read-only.");
        } catch (Exception e) { target.rollback(); throw e; }
        finally { target.setAutoCommit(true); }
    }
    static void requireEqual(Connection target, Map<String,List<Map<String,Object>>> expected) throws Exception {
        if (!equalRows(targetRows(target,expected,false),expected)) throw new IllegalStateException("Imported rows differ from plan");
        if (!"0".equals(scalar(target,"select count(*) from review_strategy s left join model_config m on s.model_config_id=m.id left join prompt p on s.prompt_id=p.id where m.id is null or (s.prompt_id is not null and (p.id is null or p.purpose<>'SPECIALIST'))"))) throw new IllegalStateException("Broken strategy reference");
        if (!"0".equals(scalar(target,"select count(*) from prompt p left join prompt_version v on p.current_version_id=v.id where v.id is null or v.prompt_id<>p.id"))) throw new IllegalStateException("Broken current version reference");
    }
    static void verify(Connection target, Path run) throws Exception {
        stage = "verify"; Map<String,List<Map<String,Object>>> expected = rows(packet(run),"targetRows"); requireEqual(target,expected);
        for (String table : TABLES) System.out.println("VERIFIED " + table + "=" + expected.get(table).size());
        System.out.println("VERIFIED all fields, credentials, version content and relationships match the plan.");
    }
    static void require(boolean condition) { if (!condition) throw new IllegalStateException("Self-test failed"); }
    static void rejected(Runnable action) { boolean rejected=false; try { action.run(); } catch (IllegalArgumentException e) { rejected=true; } require(rejected); }
    static void selfTest() throws Exception {
        stage="self-test"; ZoneId zone=ZoneId.of("Asia/Shanghai"); String date="2026-10-21 08:00:00.000";
        String a=id("project","123",date,zone); require(a.matches("[0-7][0-9A-HJKMNP-TV-Z]{25}"));
        require(a.equals(id("project","123",date,zone))); require(!a.equals(id("prompt","123",date,zone)));
        require(utc(date,zone).equals("2026-10-21 00:00:00.000")); require(type("1").equals(type("5"))); require(type("2").equals("COUPLING")); require(type("3").equals("PATTERN"));
        rejected(() -> type("4")); rejected(() -> reference(Map.of(),"missing"));
        require(tags("[\"java\",\"java\",\"business\"]").equals("java,business"));
        boolean badTag=false; try { tags("[\"a,b\"]"); } catch (IllegalArgumentException e) { badTag=true; } require(badTag);
        require(positive(JSON.readTree("{}"),"contextWindowTokens")==null); require(positive(JSON.readTree("{\"contextWindowTokens\":1048576}"),"contextWindowTokens").equals("1048576"));
        System.out.println("SELF-TEST 13 checks passed.");
    }
}
