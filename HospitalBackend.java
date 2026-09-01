import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hospital Management System - Imaging Centre Wandegeye
 * Java backend backed by MongoDB. Single file, REST API (JSON over HTTP).
 *
 * Modules: Patient Registration, OPD Consultation, Admission, Hospital Stay,
 *          Discharge, Certificates & Forms, System Configuration, Reports,
 *          Admin Panel (login, dashboard, user management, protected operations).
 *
 * Compile: javac -cp "lib/*" HospitalBackend.java
 * Run:    java -cp ".:lib/*" HospitalBackend
 * Requires a running MongoDB at mongodb://localhost:27017 (db: hms)
 */
public class HospitalBackend {

    private static final int PORT = 8080;
    private static final String HOSPITAL_NAME = "Imaging Centre Wandegeye";
    private static final String DB_NAME = "hms";

    // MongoDB
    private static MongoClient mongo;
    private static MongoDatabase db;
    private static final Map<String, MongoCollection<Document>> cols = new ConcurrentHashMap<>();

    // Document type -> collection name
    private static final Map<String, String> COLLECTION_BY_TYPE = new LinkedHashMap<>();
    private static final String[] COLLECTIONS = {
            "patients", "opd_visits", "admissions", "stays",
            "discharges", "certificates", "forms", "config", "reports", "users",
            "labs", "payroll"
    };

    // In-memory auth tokens (token -> username)
    private static final Map<String, String> sessions = new ConcurrentHashMap<>();
    private static final AtomicInteger idSeq = new AtomicInteger(0);

    public static void main(String[] args) throws IOException {
        runtime("config", "hospital.name", HOSPITAL_NAME);
        initMongo();
        seedCollections();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/hms/api", new ApiHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("=== " + HOSPITAL_NAME + " (Java Backend + MongoDB) ===");
        System.out.println("MongoDB : mongodb://localhost:27017/" + DB_NAME);
        System.out.println("API     : http://localhost:" + PORT + "/hms/api");
        System.out.println("Endpoints:");
        System.out.println("  POST /auth/login   | GET /auth/me      - staff & admin login/session");
        System.out.println("  POST /auth/register                    - create worker account [admin]");
        System.out.println("  POST /auth/logout                       - logout (invalidates token)");
        System.out.println("  GET  /admin/dashboard                   - admin stats [admin]");
        System.out.println("  GET/POST /admin/users | DELETE /admin/users/{id} - admin users [admin]");
        System.out.println("  GET  /patients   | POST /patients       - patient registration");
        System.out.println("  GET  /patients/{id}                     - patient detail");
        System.out.println("  POST /opd          | GET /opd           - OPD consultation");
        System.out.println("  POST /admissions   | GET /admissions    - admission");
        System.out.println("  POST /stays        | GET /stays         - hospital stay");
        System.out.println("  POST /discharges   | GET /discharges    - discharge");
        System.out.println("  POST /certificates | GET /certificates  - issue certificates");
        System.out.println("  POST /forms        | GET /forms         - issue forms");
        System.out.println("  POST /labs         | GET /labs          - lab results");
        System.out.println("  POST /payroll      | GET /payroll       - payroll");
        System.out.println("  GET/POST /config                        - system configuration");
        System.out.println("  GET  /reports                           - reports");
        System.out.println("Admin login:    admin / admin123");
        System.out.println("Worker login:   worker / worker123 (also: doctor/labtech)");
        System.out.println("Press Ctrl+C to stop.");
    }

    // ----- MongoDB setup -----
    private static void initMongo() {
        mongo = MongoClients.create("mongodb://localhost:27017");
        db = mongo.getDatabase(DB_NAME);
        for (String c : COLLECTIONS) {
            cols.put(c, db.getCollection(c));
        }
    }

    private static void seedCollections() {
        if (col("users").countDocuments() == 0) {
            col("users").insertOne(new Document("username", "nakteddy6@gmail.com")
                    .append("password", hash("admin123"))
                    .append("role", "ADMIN")
                    .append("fullName", "Imaging Centre Administrator"));
            col("users").insertOne(new Document("username", "admin")
                    .append("password", hash("admin123"))
                    .append("role", "ADMIN")
                    .append("fullName", "System Administrator"));
        }
        // Seed default configuration if empty
        if (col("config").countDocuments() == 0) {
            seedConfig();
        }
        // Seed worker accounts (idempotent)
        seedUserUnlessExists("worker", "worker123", "WORKER", "Front Desk Worker");
        seedUserUnlessExists("doctor", "doctor123", "WORKER", "Doctor / Clinician");
        seedUserUnlessExists("labtech", "lab123", "WORKER", "Laboratory Technician");
    }

    private static void seedUserUnlessExists(String username, String password, String role, String fullName) {
        if (col("users").find(Filters.eq("username", username)).first() == null) {
            col("users").insertOne(new Document("username", username)
                    .append("password", hash(password))
                    .append("role", role)
                    .append("fullName", fullName));
        }
    }

    private static MongoCollection<Document> col(String name) {
        return cols.get(name);
    }

    private static Document runtime(String key, String value, String type) {
        return new Document("key", key).append("value", value).append("type", type);
    }

    private static void seedConfig() {
        putConfig("hospital.name", HOSPITAL_NAME, "string");
        putConfig("hospital.address", "Wandegeya, Kampala - Uganda", "string");
        putConfig("currency", "UGX", "string");
        putConfig("bed_capacity", "120", "number");
        putConfig("max_ward_days", "30", "number");
        putConfig("registration_fee", "25000", "number");
    }

    private static void putConfig(String key, String value, String type) {
        if (col("config").find(Filters.eq("key", key)).first() == null) {
            col("config").insertOne(runtime(key, value, type));
        }
    }

    // ----- ID generation (counters collection) -----
    private static synchronized long nextSequence(String type) {
        MongoCollection<Document> counters = db.getCollection("counters");
        Document doc = counters.find(Filters.eq("_id", type)).first();
        long seq;
        if (doc == null) {
            seq = 1;
            counters.insertOne(new Document("_id", type).append("seq", seq));
        } else {
            seq = doc.getLong("seq") + 1;
            counters.updateOne(Filters.eq("_id", type),
                    new Document("$set", new Document("seq", seq)));
        }
        return seq;
    }

    private static String nextId(String prefix) {
        return prefix + "-" + nextSequence(prefix);
    }

    // ----- Password / token helpers -----
    private static String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((raw + "::hms-salt").getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(d);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h != null && h.startsWith("Bearer ")) return h.substring(7);
        return null;
    }

    private static String requireAuth(HttpExchange ex) {
        String token = bearer(ex);
        if (token == null || !sessions.containsKey(token))
            throw new SecurityException("Unauthorized: admin login required");
        return sessions.get(token);
    }

    // Require the current session to belong to an ADMIN role.
    private static String requireAdmin(HttpExchange ex) {
        String username = requireAuth(ex);
        Document user = col("users").find(Filters.eq("username", username)).first();
        if (user == null || !"ADMIN".equals(user.getString("role")))
            throw new SecurityException("Administrator access only");
        return username;
    }

    // ----- API handler -----
    private static class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String method = ex.getRequestMethod().toUpperCase();
                String path = ex.getRequestURI().getPath().replace("/hms/api", "");
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

                if (method.equals("OPTIONS")) { send(ex, 200, "{}"); return; }

                String response;
                int status = 200;
                try {
                    response = route(method, path, body, ex);
                } catch (SecurityException e) {
                    status = 401; response = jsonError(e.getMessage());
                } catch (IllegalArgumentException e) {
                    status = 400; response = jsonError(e.getMessage());
                } catch (Exception e) {
                    e.printStackTrace();
                    status = 500; response = jsonError("Server error: " + e.getMessage());
                }
                send(ex, status, response);
            } catch (Exception e) {
                send(ex, 500, jsonError("Unexpected: " + e.getMessage()));
            }
        }
    }

    private static String route(String method, String path, String body, HttpExchange ex) {
        // ---- Authentication (admins + workers) ----
        if (method.equals("POST") && path.equals("/auth/login")) return authLogin(body);
        if (method.equals("POST") && path.equals("/auth/register")) { requireAdmin(ex); return registerUser(body); }
        if (method.equals("GET") && path.equals("/auth/me")) return authMe(ex);
        if (method.equals("POST") && path.equals("/auth/logout")) return authLogout(ex);

        // ---- Admin panel ----
        if (method.equals("POST") && path.equals("/admin/login")) return adminLogin(body);
        if (method.equals("GET") && path.equals("/admin/dashboard")) { requireAdmin(ex); return adminDashboard(); }
        if (method.equals("GET") && path.equals("/admin/users")) { requireAdmin(ex); return listUsers(); }
        if (method.equals("POST") && path.equals("/admin/users")) { requireAdmin(ex); return createUser(body); }
        if (method.equals("DELETE") && path.startsWith("/admin/users/")) { requireAdmin(ex); return deleteUser(path); }

        // ---- Patient registration ----
        if (method.equals("GET") && path.equals("/patients")) return list("patients", "Patient");
        if (method.equals("POST") && path.equals("/patients")) return registerPatient(body);
        if (method.equals("GET") && path.startsWith("/patients/")) return findOne("patients", path.substring("/patients/".length()), "Patient");
        if (method.equals("DELETE") && path.startsWith("/patients/")) { requireAuth(ex); return deleteOne("patients", path, "Patient"); }

        // ---- Other modules ----
        if (method.equals("POST") && path.equals("/opd")) return createOpd(body);
        if (method.equals("GET") && path.equals("/opd")) return list("opd_visits", "OPD visit");
        if (method.equals("POST") && path.equals("/admissions")) return admit(body);
        if (method.equals("GET") && path.equals("/admissions")) return list("admissions", "Admission");
        if (method.equals("POST") && path.equals("/stays")) return recordStay(body);
        if (method.equals("GET") && path.equals("/stays")) return list("stays", "Stay");
        if (method.equals("POST") && path.equals("/discharges")) return discharge(body);
        if (method.equals("GET") && path.equals("/discharges")) return list("discharges", "Discharge");
        if (method.equals("POST") && path.equals("/certificates")) return issueCertificate(body);
        if (method.equals("GET") && path.equals("/certificates")) return list("certificates", "Certificate");
        if (method.equals("POST") && path.equals("/forms")) return issueForm(body);
        if (method.equals("GET") && path.equals("/forms")) return list("forms", "Form");

        // ---- Config ----
        if (method.equals("GET") && path.equals("/config")) return list("config", "Config");
        if (method.equals("POST") && path.equals("/config")) return updateConfig(body);

        // ---- Lab results ----
        if (method.equals("POST") && path.equals("/labs")) return recordLab(body);
        if (method.equals("GET") && path.equals("/labs")) return list("labs", "Lab result");

        // ---- Payroll ----
        if (method.equals("POST") && path.equals("/payroll")) return recordPayroll(body);
        if (method.equals("GET") && path.equals("/payroll")) return list("payroll", "Payroll record");

        // ---- Reports ----
        if (method.equals("GET") && path.equals("/reports")) return generateReports();

        if (method.equals("GET") && path.equals("/info")) return "{\"hospital\":\"" + HOSPITAL_NAME + "\"}";

        throw new IllegalArgumentException("Unknown endpoint: " + method + " " + path);
    }

    // ----- Admin panel -----
    private static String adminLogin(String body) {
        return authLogin(body);
    }

    // ----- Generic login for admins and workers -----
    private static String authLogin(String body) {
        Map<String, Object> b = Json.parseObject(body);
        String username = str(b, "username");
        String password = str(b, "password");
        Document user = col("users").find(Filters.eq("username", username)).first();
        if (user == null || !user.getString("password").equals(hash(password)))
            throw new SecurityException("Invalid username or password");
        String token = newToken();
        sessions.put(token, username);
        return Json.obj("token", token, "username", username,
                "fullName", user.getString("fullName"), "role", user.getString("role"));
    }

    // ----- Create a worker / staff account (admin only) -----
    private static String registerUser(String body) {
        Map<String, Object> b = Json.parseObject(body);
        String username = str(b, "username");
        if (col("users").find(Filters.eq("username", username)).first() != null)
            throw new IllegalArgumentException("Username already exists");
        col("users").insertOne(new Document("username", username)
                .append("password", hash(str(b, "password")))
                .append("role", str(b, "role") == null ? "WORKER" : str(b, "role"))
                .append("fullName", str(b, "fullName") == null ? username : str(b, "fullName")));
        return Json.obj("message", "Worker account created", "username", username);
    }

    // ----- Current user from token -----
    private static String authMe(HttpExchange ex) {
        String token = bearer(ex);
        if (token == null || !sessions.containsKey(token))
            throw new SecurityException("Not authenticated");
        String username = sessions.get(token);
        Document user = col("users").find(Filters.eq("username", username)).first();
        if (user == null) throw new SecurityException("Not authenticated");
        return Json.obj("username", username,
                "fullName", user.getString("fullName"), "role", user.getString("role"));
    }

    // ----- Logout: invalidate token -----
    private static String authLogout(HttpExchange ex) {
        String token = bearer(ex);
        if (token != null) sessions.remove(token);
        return Json.obj("message", "Logged out");
    }

    private static String adminDashboard() {
        long patientCount = col("patients").countDocuments();
        long admitted = col("admissions").countDocuments(Filters.eq("status", "ADMITTED"));
        long opd = col("opd_visits").countDocuments();
        long stays = col("stays").countDocuments();
        long discharges = col("discharges").countDocuments();
        long certs = col("certificates").countDocuments();
        long forms = col("forms").countDocuments();
        long labs = col("labs").countDocuments();
        long payroll = col("payroll").countDocuments();
        long users = col("users").countDocuments();
        String currency = configValue("currency");
        long regFee = configLong("registration_fee");
        return toJsonArray(List.of(
            jsonStat("Total Patients", patientCount),
            jsonStat("Currently Admitted", admitted),
            jsonStat("OPD Consultations", opd),
            jsonStat("Hospital Stays", stays),
            jsonStat("Discharges", discharges),
            jsonStat("Certificates Issued", certs),
            jsonStat("Forms Issued", forms),
            jsonStat("Lab Tests Run", labs),
            jsonStat("Payroll Records", payroll),
            jsonStat("Admin Users", users),
            jsonStat("Est. Registration Revenue (" + currency + ")", regFee * patientCount)
        ));
    }

    private static String jsonStat(String label, long value) {
        return Json.obj("label", label, "value", Long.toString(value));
    }

    private static String listUsers() {
        List<String> items = new ArrayList<>();
        for (Document d : col("users").find()) {
            items.add(Json.obj("id", d.getObjectId("_id").toHexString(),
                    "username", d.getString("username"),
                    "role", d.getString("role"),
                    "fullName", d.getString("fullName")));
        }
        return toJsonArray(items);
    }

    private static String createUser(String body) {
        Map<String, Object> b = Json.parseObject(body);
        String username = str(b, "username");
        Document existing = col("users").find(Filters.eq("username", username)).first();
        if (existing != null) throw new IllegalArgumentException("Username already exists");
        col("users").insertOne(new Document("username", username)
                .append("password", hash(str(b, "password")))
                .append("role", str(b, "role") == null ? "STAFF" : str(b, "role"))
                .append("fullName", str(b, "fullName") == null ? username : str(b, "fullName")));
        return Json.obj("message", "User created", "username", username);
    }

    private static String deleteUser(String path) {
        String id = path.substring("/admin/users/".length());
        Document user = col("users").find(Filters.eq("_id", new ObjectId(id))).first();
        if (user == null) throw new IllegalArgumentException("User not found");
        if (user.getString("username").equals("admin"))
            throw new IllegalArgumentException("Cannot delete the primary admin account");
        col("users").deleteOne(Filters.eq("_id", new ObjectId(id)));
        return Json.obj("message", "User deleted", "id", id);
    }

    // ----- Patient registration -----
    private static String registerPatient(String body) {
        Map<String, Object> b = Json.parseObject(body);
        String id = nextId("PT");
        Document doc = new Document("id", id)
                .append("name", str(b, "name"))
                .append("dob", str(b, "dob"))
                .append("gender", str(b, "gender"))
                .append("phone", str(b, "phone"))
                .append("address", str(b, "address"))
                .append("bloodGroup", str(b, "bloodGroup"))
                .append("registeredOn", today());
        col("patients").insertOne(doc);
        return docToJson(doc);
    }

    // ----- Generic read helpers -----
    private static String list(String collection, String label) {
        List<String> items = new ArrayList<>();
        for (Document d : col(collection).find()) {
            items.add(docToJson(d));
        }
        return toJsonArray(items);
    }

    private static String findOne(String collection, String id, String label) {
        Document d = col(collection).find(Filters.eq("id", id)).first();
        if (d == null) throw new IllegalArgumentException(label + " not found: " + id);
        return docToJson(d);
    }

    private static String deleteOne(String collection, String path, String label) {
        String id = path.substring(("/" + collection).length() + 1);
        ObjectId oid;
        try { oid = new ObjectId(id); } catch (Exception e) {
            throw new IllegalArgumentException("Invalid id format: " + id);
        }
        Document d = col(collection).find(Filters.eq("_id", oid)).first();
        if (d == null) throw new IllegalArgumentException(label + " not found");
        col(collection).deleteOne(Filters.eq("_id", oid));
        return Json.obj("message", label + " deleted", "id", id);
    }

    // ----- OPD -----
    private static String createOpd(String body) {
        Map<String, Object> b = Json.parseObject(body);
        String pid = str(b, "patientId");
        requirePatient(pid);
        Document doc = new Document("id", nextId("OPD"))
                .append("patientId", pid)
                .append("doctor", str(b, "doctor"))
                .append("symptoms", str(b, "symptoms"))
                .append("diagnosis", str(b, "diagnosis"))
                .append("prescription", str(b, "prescription"))
                .append("date", today());
        col("opd_visits").insertOne(doc);
        return docToJson(doc);
    }

    // ----- Admission -----
    private static String admit(String body) {
        Map<String, Object> b = Json.parseObject(body);
        String pid = str(b, "patientId");
        requirePatient(pid);
        Document doc = new Document("id", nextId("ADM"))
                .append("patientId", pid)
                .append("ward", str(b, "ward"))
                .append("bed", str(b, "bed"))
                .append("doctor", str(b, "doctor"))
                .append("reason", str(b, "reason"))
                .append("admittedOn", today())
                .append("status", "ADMITTED");
        col("admissions").insertOne(doc);
        return docToJson(doc);
    }

    // ----- Stay -----
    private static String recordStay(String body) {
        Map<String, Object> b = Json.parseObject(body);
        String pid = str(b, "patientId");
        requirePatient(pid);
        Document doc = new Document("id", nextId("STAY"))
                .append("patientId", pid)
                .append("ward", str(b, "ward"))
                .append("bed", str(b, "bed"))
                .append("diagnosis", str(b, "diagnosis"))
                .append("notes", str(b, "notes"))
                .append("days", num(b, "days"));
        col("stays").insertOne(doc);
        return docToJson(doc);
    }

    // ----- Discharge -----
    private static String discharge(String body) {
        Map<String, Object> b = Json.parseObject(body);
        String pid = str(b, "patientId");
        requirePatient(pid);
        Document doc = new Document("id", nextId("DCH"))
                .append("patientId", pid)
                .append("dischargeType", str(b, "dischargeType"))
                .append("summary", str(b, "summary"))
                .append("dischargedOn", today());
        col("discharges").insertOne(doc);
        // mark any open admission as discharged
        col("admissions").updateMany(
                Filters.and(Filters.eq("patientId", pid), Filters.eq("status", "ADMITTED")),
                Updates.set("status", "DISCHARGED"));
        return docToJson(doc);
    }

    // ----- Certificates & Forms -----
    private static String issueCertificate(String body) {
        Map<String, Object> b = Json.parseObject(body);
        String pid = str(b, "patientId");
        requirePatient(pid);
        Document doc = new Document("id", nextId("CRT"))
                .append("patientId", pid)
                .append("type", str(b, "type"))
                .append("content", str(b, "content"))
                .append("issuedOn", today());
        col("certificates").insertOne(doc);
        return docToJson(doc);
    }

    private static String issueForm(String body) {
        Map<String, Object> b = Json.parseObject(body);
        Document doc = new Document("id", nextId("FRM"))
                .append("title", str(b, "title"))
                .append("fields", str(b, "fields"))
                .append("issuedOn", today());
        col("forms").insertOne(doc);
        return docToJson(doc);
    }

    // ----- Lab results -----
    private static String recordLab(String body) {
        Map<String, Object> b = Json.parseObject(body);
        String pid = str(b, "patientId");
        requirePatient(pid);
        Document doc = new Document("id", nextId("LAB"))
                .append("patientId", pid)
                .append("test", str(b, "test"))
                .append("value", str(b, "value"))
                .append("unit", str(b, "unit"))
                .append("status", str(b, "status"))
                .append("date", today());
        col("labs").insertOne(doc);
        return docToJson(doc);
    }

    // ----- Payroll -----
    private static String recordPayroll(String body) {
        Map<String, Object> b = Json.parseObject(body);
        Document doc = new Document("id", nextId("PY"))
                .append("name", str(b, "name"))
                .append("role", str(b, "role"))
                .append("month", str(b, "month"))
                .append("salary", str(b, "salary"))
                .append("allowance", str(b, "allowance"))
                .append("deductions", str(b, "deductions"))
                .append("status", str(b, "status"))
                .append("date", today());
        col("payroll").insertOne(doc);
        return docToJson(doc);
    }

    // ----- Config -----
    private static String updateConfig(String body) {
        Map<String, Object> b = Json.parseObject(body);
        String key = str(b, "key");
        String value = str(b, "value");
        Document existing = col("config").find(Filters.eq("key", key)).first();
        if (existing == null) {
            col("config").insertOne(new Document("key", key)
                    .append("value", value).append("type", "string"));
        } else {
            col("config").updateOne(Filters.eq("key", key), Updates.set("value", value));
            existing = col("config").find(Filters.eq("key", key)).first();
        }
        return existing == null ? Json.obj("key", key, "value", value) : docToJson(existing);
    }

    private static String configValue(String key) {
        Document d = col("config").find(Filters.eq("key", key)).first();
        return d == null ? "" : d.getString("value");
    }

    private static long configLong(String key) {
        try { return Long.parseLong(configValue(key)); } catch (Exception e) { return 0; }
    }

    // ----- Reports -----
    private static String generateReports() {
        List<String> reports = new ArrayList<>();
        reports.add(report("Patient Registration Report", "Total registered patients: " + col("patients").countDocuments()));
        reports.add(report("OPD Consultation Report", "Total OPD consultations: " + col("opd_visits").countDocuments()));
        reports.add(report("Admission Report", "Total admissions: " + col("admissions").countDocuments() +
                " | currently admitted: " + col("admissions").countDocuments(Filters.eq("status", "ADMITTED"))));
        reports.add(report("Hospital Stay Report", "Total stay records: " + col("stays").countDocuments()));
        reports.add(report("Discharge Report", "Total discharges: " + col("discharges").countDocuments()));
        reports.add(report("Certificate & Forms Report", "Certificates issued: " + col("certificates").countDocuments() +
                " | forms issued: " + col("forms").countDocuments()));
        reports.add(report("Laboratory Report", "Total lab tests run: " + col("labs").countDocuments()));
        reports.add(report("Payroll Report", "Total payroll records: " + col("payroll").countDocuments()));
        reports.add(report("Financial Report", "Estimated revenue from registration: " +
                configValue("currency") + " " + (configLong("registration_fee") * col("patients").countDocuments())));
        return toJsonArray(reports);
    }

    private static String report(String title, String detail) {
        return Json.obj("title", title, "detail", detail);
    }

    // ----- Helpers -----
    private static void requirePatient(String id) {
        Document p = col("patients").find(Filters.eq("id", id)).first();
        if (p == null) throw new IllegalArgumentException("Patient does not exist: " + id);
    }

    private static void send(HttpExchange ex, int status, String json) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static String today() { return LocalDate.now().toString(); }

    private static String str(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v == null ? null : v.toString().trim();
    }

    private static int num(Map<String, Object> b, String key) {
        Object v = b.get(key);
        if (v == null) return 0;
        try { return (int) Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    private static String jsonError(String message) {
        return "{\"error\":\"" + message.replace("\"", "'") + "\"}";
    }

    private static String toJsonArray(List<String> items) {
        return "[" + String.join(",", items) + "]";
    }

    // Convert a Mongo Document to a flat JSON string (uses the business "id" field, not _id)
    private static String docToJson(Document d) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String k : d.keySet()) {
            if (k.equals("_id")) continue;
            first = appendField(sb, first, k, stringify(d.get(k)));
        }
        sb.append("}");
        return sb.toString();
    }

    private static boolean appendField(StringBuilder sb, boolean first, String key, String value) {
        if (!first) sb.append(",");
        sb.append("\"").append(key).append("\":\"").append(escape(value)).append("\"");
        return false;
    }

    private static String stringify(Object v) {
        if (v == null) return "";
        return String.valueOf(v);
    }

    private static String escape(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ----- Tiny JSON parser (no external library) -----
    static class Json {
        static Map<String, Object> parseObject(String s) {
            Map<String, Object> map = new LinkedHashMap<>();
            if (s == null || s.isBlank()) return map;
            s = s.trim();
            int i = s.indexOf('{');
            int end = s.lastIndexOf('}');
            if (i < 0 || end < 0) return map;
            String inner = s.substring(i + 1, end);
            for (String pair : splitPairs(inner)) {
                String[] kv = pair.split(":", 2);
                if (kv.length != 2) continue;
                String key = kv[0].trim().replaceAll("^\"|\"$", "");
                String val = kv[1].trim().replaceAll("^\"|\"$", "");
                map.put(key, val);
            }
            return map;
        }

        private static List<String> splitPairs(String inner) {
            List<String> out = new ArrayList<>();
            int depth = 0;
            StringBuilder cur = new StringBuilder();
            for (char c : inner.toCharArray()) {
                if (c == '{' || c == '[') depth++;
                if (c == '}' || c == ']') depth--;
                if (c == ',' && depth == 0) { out.add(cur.toString()); cur.setLength(0); }
                else cur.append(c);
            }
            if (cur.length() > 0) out.add(cur.toString());
            return out;
        }

        static String obj(Object... kv) {
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < kv.length; i += 2) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(kv[i]).append("\":\"").append(escape(String.valueOf(kv[i + 1]))).append("\"");
            }
            return sb.append("}").toString();
        }

        private static String escape(String v) {
            return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
