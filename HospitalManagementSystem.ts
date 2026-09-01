/**
 * Hospital Management System - Imaging Centre Wandegeye
 * TypeScript Frontend - drives the CareSync HTML workspace against the
 * MongoDB-backed Java REST API.
 *
 * Modules: Dashboard, Patient Registration, OPD Consultation, Lab Results,
 *          In-Patient Care (Admit/Stay/Discharge), Documentation
 *          (Certificates/Forms), Payroll [admin], Configuration [admin],
 *          Reports [admin], Admin Login.
 *
 * Compile:  npx tsc HospitalManagementSystem.ts --outDir .
 * Open:     HospitalManagementSystem.html
 */
const API_BASE: string = "http://localhost:8080/hms/api";
const TOKEN_KEY: string = "hms_admin_token";
const ROLE_KEY: string = "hms_user_role";
const NAME_KEY: string = "hms_user_name";

interface Patient {
    id: string; name: string; dob: string; gender: string;
    phone: string; address: string; bloodGroup: string; registeredOn: string;
}
interface Lab { id: string; patientId: string; test: string; value: string; unit: string; status: string; date: string; }
interface Payroll { id: string; name: string; role: string; month: string; salary: string; allowance: string; deductions: string; status: string; }
interface ConfigEntry { key: string; value: string; type: string; }
interface Report { title: string; detail: string; }
interface Stat { label: string; value: string; }

// ----- tokens / storage -----
function getToken(): string | null { return localStorage.getItem(TOKEN_KEY); }
function setToken(t: string): void { localStorage.setItem(TOKEN_KEY, t); }
function clearToken(): void { localStorage.removeItem(TOKEN_KEY); }
function getRole(): string | null { return localStorage.getItem(ROLE_KEY) || "WORKER"; }
function setRole(r: string): void { localStorage.setItem(ROLE_KEY, r); }
function clearRole(): void { localStorage.removeItem(ROLE_KEY); }
function getUserName(): string | null { return localStorage.getItem(NAME_KEY); }
function setUserName(n: string): void { localStorage.setItem(NAME_KEY, n); }
function clearUserName(): void { localStorage.removeItem(NAME_KEY); }
function isAdmin(): boolean { return getRole() === "ADMIN"; }

async function api<T>(method: string, path: string, body?: object, authenticated: boolean = false): Promise<T> {
    const headers: Record<string, string> = { "Content-Type": "application/json" };
    if (authenticated) { const t = getToken(); if (t) headers["Authorization"] = "Bearer " + t; }
    const res = await fetch(API_BASE + path, {
        method, headers, body: body ? JSON.stringify(body) : undefined,
    });
    const data: unknown = await res.json();
    if (!res.ok) {
        throw new Error((data as { error?: string }).error || "Request failed (HTTP " + res.status + ")");
    }
    return data as T;
}

// ----- DOM helpers -----
function byId<T extends HTMLElement = HTMLElement>(id: string): T {
    const el = document.getElementById(id);
    if (!el) throw new Error("Missing element: #" + id);
    return el as T;
}
function val(id: string): string { return (byId<HTMLInputElement>(id).value || "").trim(); }
function setOut(id: string, html: string, show: boolean = true): void {
    const el = document.getElementById(id);
    if (el) { el.innerHTML = html; (el as HTMLElement).style.display = show ? "block" : "none"; }
}

// ----- Toast notifications -----
function toast(msg: string, type: "success" | "error" = "success"): void {
    const c = document.getElementById("toast-container");
    if (!c) { alert(msg); return; }
    const t = document.createElement("div");
    t.className = "toast " + (type === "error" ? "badge-error" : "badge-success");
    t.textContent = msg;
    c.appendChild(t);
    setTimeout(() => t.remove(), 3500);
}

// ----- Patient dropdowns -----
async function populatePatientDropdowns(): Promise<void> {
    try {
        const list = await api<Patient[]>("GET", "/patients");
        document.querySelectorAll<HTMLSelectElement>(".select-patient-dropdown").forEach(sel => {
            const current = sel.value;
            sel.innerHTML = list.length
                ? "<option value=''>-- Select Patient --</option>" +
                  list.map(p => `<option value="${p.id}">${p.id} — ${p.name}</option>`).join("")
                : "<option value=''>-- No Patients Registered --</option>";
            if (current) sel.value = current;
        });
    } catch { /* ignore */ }
}

// ================= Admin panel =================
function isAuthed(): boolean { return !!getToken(); }

function applyAdminUi(): void {
    const authed = isAuthed();
    const admin = authed && isAdmin();
    // toggle admin badge (admins only)
    byId("admin-indicator-badge").style.display = admin ? "block" : "none";
    // unlock / lock secured (admin-only) panels
    document.querySelectorAll<HTMLElement>(".admin-secured").forEach((panel) => {
        const lock = panel.querySelector<HTMLElement>(".lock-overlay");
        const content = panel.querySelector<HTMLElement>(".admin-content");
        if (lock) lock.style.display = admin ? "none" : "";
        if (content) content.style.display = admin ? "block" : "none";
    });
    // show admin-only sidebar nav buttons only for admins
    document.querySelectorAll<HTMLElement>(".nav-button.locked-nav").forEach(b => {
        b.style.display = admin ? "" : "none";
    });
    // login panel cards
    if (authed) {
        byId("login-form-card").style.display = "none";
        byId("logged-in-card").style.display = "block";
    } else {
        byId("login-form-card").style.display = "block";
        byId("logged-in-card").style.display = "none";
    }
}

async function refreshDashboardStats(): Promise<void> {
    // stop-gap: update patient-based stats from public endpoints
    try {
        const patients = await api<Patient[]>("GET", "/patients");
        byId("stat-patients-count").textContent = String(patients.length);
    } catch { /* ignore */ }
    try {
        const admissions = await api<any[]>("GET", "/admissions");
        const admitted = admissions.filter(a => a.status === "ADMITTED").length;
        byId("stat-admissions-count").textContent = String(admitted);
    } catch { /* ignore */ }
    try {
        const labs = await api<Lab[]>("GET", "/labs");
        byId("stat-labs-count").textContent = String(labs.length);
    } catch { /* ignore */ }
    // revenue: admins get the protected figure; workers get an estimate
    try {
        if (isAdmin()) {
            const stats = await api<Stat[]>("GET", "/admin/dashboard");
            const rev = stats.find(s => s.label.indexOf("Registration Revenue") >= 0);
            if (rev) byId("stat-revenue").textContent = rev.value;
        }
    } catch { /* ignore */ }
}

// ---- app object consumed by the HTML ----
const app = {
    // ----- Tab / navigation -----
    switchTab(tab: string): void {
        // admin-only tabs require an authenticated admin
        const adminOnly = ["cfg", "rpt", "pay"];
        if (adminOnly.includes(tab)) {
            if (!isAuthed()) { this.switchTab("login"); return; }
            if (!isAdmin()) { toast("Admin access only", "error"); this.switchTab("dashboard"); return; }
        }
        document.querySelectorAll<HTMLElement>(".panel").forEach(p => p.style.display = "none");
        const target = document.getElementById(tab);
        if (target) target.style.display = "block";
        document.querySelectorAll<HTMLElement>(".nav-button").forEach(b => b.classList.remove("active"));
        const btn = document.querySelector<HTMLElement>(`.nav-button[data-tab="${tab}"]`);
        if (btn) btn.classList.add("active");
        // update header title
        if (tab === "dashboard") byId("header-display-title").textContent = "Dashboard Overview";
        else {
            const label = btn ? btn.textContent!.trim() : tab;
            byId("header-display-title").textContent = label;
        }
        window.scrollTo(0, 0);
    },

    switchSubTab(parent: string, sub: string): void {
        const p = document.getElementById(parent);
        if (!p) return;
        p.querySelectorAll<HTMLElement>(".sub-panel").forEach(s => s.style.display = "none");
        const t = p.querySelector<HTMLElement>("#" + sub);
        if (t) t.style.display = "block";
        p.querySelectorAll<HTMLElement>(".sub-tab-btn").forEach(b => b.classList.remove("active"));
        const b = p.querySelector<HTMLElement>(`.sub-tab-btn[data-subtab="${sub}"]`);
        if (b) b.classList.add("active");
    },

    // ----- Admin login / logout (in-app login panel) -----
    async loginAdmin(): Promise<void> {
        const username = val("li-username");
        const password = val("li-password");
        if (!username || !password) { toast("Enter username and password", "error"); return; }
        try {
            const r = await api<any>("POST", "/auth/login", { username, password });
            setToken(r.token);
            setRole(r.role || "WORKER");
            setUserName(r.fullName || r.username);
            applyAdminUi();
            toast("Welcome, " + (r.fullName || "Administrator"));
            await refreshDashboardStats();
            this.switchTab("dashboard");
        } catch (e: any) {
            toast("Login failed: " + e.message, "error");
        }
    },

    logoutAdmin(): void {
        clearToken();
        clearRole();
        clearUserName();
        applyAdminUi();
        toast("Logged out. Admin sections locked.");
        this.switchTab("dashboard");
    },

    // ----- Patient registration -----
    async register(): Promise<void> {
        try {
            const p = await api<Patient>("POST", "/patients", {
                name: val("r-name"), dob: val("r-dob"), gender: val("r-gender"),
                phone: val("r-phone"), address: val("r-address"), bloodGroup: val("r-blood"),
            });
            toast("Patient registered: " + p.id + " — " + p.name);
            this.loadPatients();
            populatePatientDropdowns();
            refreshDashboardStats();
        } catch (e: any) { toast(e.message, "error"); }
    },

    async loadPatients(): Promise<void> {
        try {
            const list = await api<Patient[]>("GET", "/patients");
            const c = document.getElementById("patients-table-container");
            if (!c) return;
            c.innerHTML = list.length
                ? "<table><tr><th>ID</th><th>Name</th><th>Gender</th><th>Phone</th><th>Blood</th><th>Registered</th></tr>" +
                  list.map(p => `<tr><td>${p.id}</td><td>${p.name}</td><td>${p.gender}</td><td>${p.phone}</td><td>${p.bloodGroup}</td><td>${p.registeredOn}</td></tr>`).join("")
                : "<p style='padding:1rem;color:var(--text-muted);'>No patients registered yet.</p>";
        } catch (e: any) { toast(e.message, "error"); }
    },

    // ----- OPD -----
    async createOpd(): Promise<void> {
        try {
            const r = await api<any>("POST", "/opd", {
                patientId: val("o-pid"), doctor: val("o-doctor"), symptoms: val("o-symptoms"),
                diagnosis: val("o-diagnosis"), prescription: val("o-prescription"),
            });
            setOut("opd-out", `<b>Saved:</b> ${r.id} — ${r.diagnosis} prescribed by ${r.doctor}`);
            toast("OPD consultation saved");
        } catch (e: any) { toast(e.message, "error"); }
    },

    // ----- Lab results -----
    async recordLab(): Promise<void> {
        try {
            const r = await api<Lab>("POST", "/labs", {
                patientId: val("l-pid"), test: val("l-test"), value: val("l-value"),
                unit: val("l-unit"), status: val("l-status"),
            });
            setOut("labs-out", `<b>Saved:</b> ${r.test} = ${r.value} ${r.unit} (${r.status})`);
            toast("Lab result recorded");
            this.loadLabs();
            refreshDashboardStats();
        } catch (e: any) { toast(e.message, "error"); }
    },

    async loadLabs(): Promise<void> {
        try {
            const list = await api<Lab[]>("GET", "/labs");
            const c = document.getElementById("labs-table-container");
            if (!c) return;
            c.innerHTML = list.length
                ? "<table><tr><th>Test</th><th>Result</th><th>Unit</th><th>Status</th><th>Date</th></tr>" +
                  list.map(l => `<tr><td>${l.test}</td><td>${l.value}</td><td>${l.unit}</td><td>${l.status}</td><td>${l.date}</td></tr>`).join("")
                : "<p style='padding:1rem;color:var(--text-muted);'>No lab results recorded yet.</p>";
        } catch (e: any) { toast(e.message, "error"); }
    },

    // ----- In-Patient: Admit -----
    async admit(): Promise<void> {
        try {
            const r = await api<any>("POST", "/admissions", {
                patientId: val("a-pid"), ward: val("a-ward"), bed: val("a-bed"),
                doctor: val("a-doctor"), reason: val("a-reason"),
            });
            setOut("adm-out", `<b>Admitted:</b> ${r.patientId} → ${r.ward}/${r.bed} (${r.status})`);
            toast("Patient admitted");
            refreshDashboardStats();
        } catch (e: any) { toast(e.message, "error"); }
    },

    // ----- In-Patient: Stay -----
    async recordStay(): Promise<void> {
        try {
            const r = await api<any>("POST", "/stays", {
                patientId: val("s-pid"), ward: val("s-ward"), bed: val("s-bed"),
                diagnosis: val("s-diagnosis"), notes: val("s-notes"), days: parseInt(val("s-days") || "0"),
            });
            setOut("stay-out", `<b>Stay recorded:</b> ${r.id} — ${r.days} day(s)`);
            toast("Stay record saved");
        } catch (e: any) { toast(e.message, "error"); }
    },

    // ----- In-Patient: Discharge -----
    async discharge(): Promise<void> {
        try {
            const r = await api<any>("POST", "/discharges", {
                patientId: val("d-pid"), dischargeType: val("d-type"), summary: val("d-summary"),
            });
            setOut("dch-out", `<b>Discharged:</b> ${r.patientId} (${r.dischargeType}) on ${r.dischargedOn}`);
            toast("Patient discharged");
            refreshDashboardStats();
        } catch (e: any) { toast(e.message, "error"); }
    },

    // ----- Documentation: Certificates -----
    async issueCertificate(): Promise<void> {
        try {
            const r = await api<any>("POST", "/certificates", {
                patientId: val("c-pid"), type: val("c-type"), content: val("c-content"),
            });
            setOut("crt-out", `<b>Certificate issued:</b> ${r.id} (${r.type})`);
            toast("Certificate issued");
        } catch (e: any) { toast(e.message, "error"); }
    },

    // ----- Documentation: Forms -----
    async issueForm(): Promise<void> {
        try {
            const r = await api<any>("POST", "/forms", {
                title: val("f-title"), fields: val("f-fields"),
            });
            setOut("frm-out", `<b>Form issued:</b> ${r.id} — ${r.title}`);
            toast("Form issued");
        } catch (e: any) { toast(e.message, "error"); }
    },

    // ----- Payroll [admin] -----
    async recordPayroll(): Promise<void> {
        if (!isAuthed()) { toast("Admin login required", "error"); this.switchTab("login"); return; }
        try {
            const r = await api<Payroll>("POST", "/payroll", {
                name: val("py-name"), role: val("py-role"), month: val("py-month"),
                salary: val("py-salary"), allowance: val("py-allowance"),
                deductions: val("py-deductions"), status: val("py-status"),
            });
            setOut("pay-out", `<b>Payslip:</b> ${r.name} — ${r.month} (${r.status})`);
            toast("Payroll processed");
            this.loadPayroll();
        } catch (e: any) { toast(e.message, "error"); }
    },

    async loadPayroll(): Promise<void> {
        if (!isAuthed()) return;
        try {
            const list = await api<Payroll[]>("GET", "/payroll", undefined);
            const c = document.getElementById("payroll-table-container");
            if (!c) return;
            c.innerHTML = list.length
                ? "<table><tr><th>Name</th><th>Role</th><th>Month</th><th>Salary</th><th>Status</th></tr>" +
                  list.map(p => `<tr><td>${p.name}</td><td>${p.role}</td><td>${p.month}</td><td>${p.salary}</td><td>${p.status}</td></tr>`).join("")
                : "<p style='padding:1rem;color:var(--text-muted);'>No payroll records yet.</p>";
        } catch (e: any) { toast(e.message, "error"); }
    },

    // ----- Configuration [admin] -----
    async loadConfig(): Promise<void> {
        if (!isAuthed()) { toast("Admin login required", "error"); this.switchTab("login"); return; }
        try {
            const list = await api<ConfigEntry[]>("GET", "/config", undefined);
            const c = document.getElementById("cfg-list");
            if (!c) return;
            c.innerHTML = list.map(cfg =>
                `<div class="cfg-row"><label>${cfg.key}</label>
                 <input id="cfg-${cfg.key}" value="${cfg.value}">
                 <button class="btn btn-secondary" onclick="app.saveConfig('${cfg.key}')">Save</button></div>`
            ).join("");
            setOut("cfg-out", `Hospital: <b>${(list.find(k => k.key === "hospital.name") || {}).value || ""}</b> — ${list.length} settings loaded.`, false);
        } catch (e: any) { toast(e.message, "error"); }
    },

    async saveConfig(key: string): Promise<void> {
        const input = document.getElementById("cfg-" + key) as HTMLInputElement;
        try {
            const r = await api<ConfigEntry>("POST", "/config", { key, value: input.value });
            toast("Updated " + r.key + " = " + r.value);
        } catch (e: any) { toast(e.message, "error"); }
    },

    // ----- Reports [admin] -----
    async loadReports(): Promise<void> {
        if (!isAuthed()) { toast("Admin login required", "error"); this.switchTab("login"); return; }
        try {
            const list = await api<Report[]>("GET", "/reports", undefined);
            const c = document.getElementById("reports-output-container");
            if (c) c.innerHTML = list.map(r =>
                `<div class="report-item"><b>${r.title}:</b> ${r.detail}</div>`).join("");
            setOut("rpt-out", "Reports refreshed.", false);
            toast("Reports generated");
        } catch (e: any) { toast(e.message, "error"); }
    },
};

(window as any).app = app;

// ================= Init =================
function boot(): void {
    applyAdminUi();
    const now = new Date();
    const timeEl = document.getElementById("live-time");
    if (timeEl) timeEl.textContent = now.toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric" });

    // The dashboard opens directly — authentication is NOT required to enter.
    // Admin-only sections stay locked until an admin logs in via the login panel.

    // sidebar nav click -> switchTab
    document.querySelectorAll<HTMLElement>(".nav-button").forEach(btn => {
        btn.addEventListener("click", () => {
            const tab = btn.getAttribute("data-tab") || "";
            app.switchTab(tab);
            if (tab === "reg") { app.loadPatients(); }
            if (tab === "labs") { app.loadLabs(); }
            if (tab === "pay" && isAuthed()) { app.loadPayroll(); }
            if (tab === "cfg" && isAuthed()) { app.loadConfig(); }
            if (tab === "rpt" && isAuthed()) { app.loadReports(); }
            if (tab === "dashboard") { refreshDashboardStats(); }
        });
    });
    // login button in sidebar
    const lbtn = document.getElementById("sidebar-login-btn");
    if (lbtn) lbtn.addEventListener("click", () => app.switchTab("login"));
    // sign out button in sidebar
    const sbtn = document.getElementById("sidebar-logout-btn");
    if (sbtn) sbtn.addEventListener("click", () => app.logoutAdmin());

    populatePatientDropdowns();
    refreshDashboardStats();
}

if (document.readyState === "loading") window.addEventListener("DOMContentLoaded", boot);
else boot();
