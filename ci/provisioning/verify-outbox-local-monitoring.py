#!/usr/bin/env python3
"""Run the P7.22 local-only database/metrics/notification/dashboard drill."""

import argparse
import base64
import datetime
import hashlib
import json
import math
import pathlib
import secrets
import socket
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request


ROOT = pathlib.Path(__file__).resolve().parent.parent
IMAGES = {
    "java": "eclipse-temurin:21-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f",
    "prometheus": "prom/prometheus@sha256:4a61322ac1103a0e3aea2a61ef1718422a48fa046441f299d71e660a3bc71ae9",
    "alertmanager": "prom/alertmanager:v0.28.1@sha256:27c475db5fb156cab31d5c18a4251ac7ed567746a2483ff264516437a39b15ba",
    "grafana": "grafana/grafana@sha256:83749231c3835e390a3144e5e940203e42b9589761f20ef3169c716e734ad505",
}
for property_line in (ROOT / "gradle.properties").read_text().splitlines():
    if property_line.startswith("postgresqlImage="):
        IMAGES["postgres"] = property_line.split("=", 1)[1]
    if property_line.startswith("rabbitmqImage="):
        IMAGES["rabbitmq"] = property_line.split("=", 1)[1]


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def command(arguments, data=None, sensitive=False):
    result = subprocess.run(arguments, input=data, text=True, capture_output=True, check=False)
    if result.returncode:
        detail = "Sensitive command output withheld" if sensitive else result.stderr
        raise RuntimeError(f"Command failed: {arguments[0]} {arguments[1:3]}\n{detail}")
    return result.stdout


def wait_for(description, assertion, timeout=180):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            value = assertion()
            if value:
                return value
        except (urllib.error.URLError, TimeoutError, ConnectionError, RuntimeError) as error:
            last_error = error
        time.sleep(2)
    raise RuntimeError(f"Timed out: {description}; last error: {last_error}")


class Drill:
    def __init__(self, options):
        self.options = options
        self.namespace = options.namespace
        self.kube = ["kubectl", "--context", options.context, "--namespace", self.namespace]
        self.output = pathlib.Path(options.output).resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        self.credentials = {name: secrets.token_hex(24) for name in
                            ("postgres", "migrator", "worker", "broker", "metrics", "grafana")}
        self.forwards = []
        self.events = []
        self.ports = {}
        self.started = utc()
        self.created = False

    def kubectl(self, *arguments, data=None, sensitive=False):
        return command(self.kube + list(arguments), data, sensitive=sensitive)

    def record(self, name, value):
        (self.output / f"{name}.json").write_text(json.dumps(value, indent=2) + "\n")

    def checkpoint(self, name, **details):
        event = {"at": utc(), "step": name, **details}
        self.events.append(event)
        self.record("timeline", self.events)
        print(json.dumps(event), flush=True)

    def apply(self, kind, name, **specification):
        resource = {"apiVersion": "apps/v1" if kind == "Deployment" else "v1",
                    "kind": kind, "metadata": {"name": name, "namespace": self.namespace,
                    "labels": {"cbt.meldtech.org/drill": "p722-local", "app": name}}, **specification}
        self.kubectl("create" if kind == "Secret" else "apply", "-f", "-",
                     data=json.dumps(resource), sensitive=kind == "Secret")

    def config(self, name, data):
        self.apply("ConfigMap", name, data=data)

    def secret_env(self, variable, key):
        return {"name": variable, "valueFrom": {"secretKeyRef": {"name": "credentials", "key": key}}}

    def workload(self, name, image, port, env=None, args=None, mounts=None,
                 volumes=None, executable=None, readiness=True):
        container = {"name": name, "image": image, "imagePullPolicy": "IfNotPresent",
                     "ports": [{"containerPort": port}], "env": env or [],
                     "volumeMounts": mounts or [],
                     "resources": {"requests": {"cpu": "100m", "memory": "128Mi"},
                                   "limits": {"cpu": "2", "memory": "768Mi"}}}
        if args:
            container["args"] = args
        if executable:
            container["command"] = executable
        if readiness:
            container["readinessProbe"] = {"tcpSocket": {"port": port}, "periodSeconds": 2}
        self.apply("Deployment", name, spec={"replicas": 1, "strategy": {"type": "Recreate"},
                   "selector": {"matchLabels": {"app": name}}, "template": {
                       "metadata": {"labels": {"app": name}},
                       "spec": {"automountServiceAccountToken": False,
                                "containers": [container], "volumes": volumes or []}}})
        self.apply("Service", name, spec={"type": "ClusterIP", "selector": {"app": name},
                   "ports": [{"port": port, "targetPort": port}]})

    def pod(self, name):
        pods = json.loads(self.kubectl("get", "pods", "-l", f"app={name}", "-o", "json"))["items"]
        return next((pod["metadata"]["name"] for pod in pods
                     if not pod["metadata"].get("deletionTimestamp") and
                     pod.get("status", {}).get("phase") == "Running"), None)

    def ready(self, name):
        return self.kubectl("get", "deployment", name, "-o", "jsonpath={.status.readyReplicas}") == "1"

    def migration_complete(self, pod):
        phase = self.kubectl("get", "pod", pod, "-o", "jsonpath={.status.phase}")
        if phase == "Failed":
            raise ValueError("Migration failed; inspect the local migrator pod log")
        return phase == "Succeeded"

    def forward(self, name, remote):
        with socket.socket() as candidate:
            candidate.bind(("127.0.0.1", 0))
            port = candidate.getsockname()[1]
        logfile = (self.output / f"forward-{name}.log").open("w")
        process = subprocess.Popen(self.kube + ["port-forward", "--address", "127.0.0.1",
                                   f"service/{name}", f"{port}:{remote}"], stdout=logfile, stderr=logfile)
        self.forwards.append((process, logfile))
        self.ports[name] = port
        def listening():
            if process.poll() is not None:
                raise ValueError(f"Port-forward exited for {name}")
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return True
        wait_for(name + " port-forward", listening, 30)

    def http(self, name, path, payload=None, credentials=None):
        headers = {}
        if credentials:
            username, password = credentials
            headers["Authorization"] = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()
        data = None if payload is None else json.dumps(payload).encode()
        if data:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(f"http://127.0.0.1:{self.ports[name]}{path}",
                                         data=data, headers=headers)
        with urllib.request.urlopen(request, timeout=10) as response:
            body = response.read().decode()
            return json.loads(body) if body and body[0] in "[{" else body

    def query(self, expression, grafana=False):
        path = "/api/v1/query?" + urllib.parse.urlencode({"query": expression})
        response = self.http("grafana" if grafana else "prometheus",
                             "/api/datasources/proxy/uid/p722" + path if grafana else path,
                             credentials=("admin", self.credentials["grafana"]) if grafana else None)
        if response.get("status") != "success":
            raise RuntimeError("Prometheus query failed")
        return response["data"]["result"]

    def scalar(self, expression):
        values = self.query(expression)
        return float(values[0]["value"][1]) if values else None

    def psql(self, sql, sensitive=False):
        return self.kubectl("exec", "-i", "deployment/postgres", "--", "psql", "-X", "-A", "-t",
                            "-U", "postgres", "-d", "p722", "-v", "ON_ERROR_STOP=1", data=sql,
                            sensitive=sensitive)

    def set_role_password(self, role, credential):
        self.psql(f"\\set role_name {role}\n"
                  f"\\set role_password {self.credentials[credential]}\n"
                  'ALTER ROLE :"role_name" PASSWORD :\'role_password\';\n', sensitive=True)

    def fixture_pod(self, name, mode, env):
        launch = "while [ ! -f /application/ready ]; do sleep 1; done; exec java -Xmx384m -jar /application/drill.jar " + mode
        if name == "migrator":
            self.apply("Pod", name, spec={"restartPolicy": "Never", "automountServiceAccountToken": False,
                       "containers": [{"name": name, "image": IMAGES["java"], "env": env,
                           "command": ["sh", "-c", launch],
                           "volumeMounts": [{"name": "application", "mountPath": "/application"}]}],
                       "volumes": [{"name": "application", "emptyDir": {}}]})
        else:
            self.workload(name, IMAGES["java"], 8080 if name == "receiver" else 9091, env=env,
                          executable=["sh", "-c", launch],
                          mounts=[{"name": "application", "mountPath": "/application"}],
                          volumes=[{"name": "application", "emptyDir": {}}])
        pod = wait_for(f"{name} pod running", lambda: self.pod(name))
        self.kubectl("cp", str(self.jar), f"{pod}:/application/drill.jar")
        return pod

    def setup(self):
        if not self.namespace.startswith("p722-local"):
            raise RuntimeError("Use an isolated p722-local namespace")
        cluster = json.loads(command(["kubectl", "--context", self.options.context,
                                      "config", "view", "--minify", "-o", "json"]))
        server = cluster["clusters"][0]["cluster"]["server"]
        if urllib.parse.urlparse(server).hostname not in ("127.0.0.1", "localhost", "::1", "kubernetes.docker.internal"):
            raise RuntimeError("This drill only permits a local Kubernetes API")
        self.kubectl("create", "namespace", self.namespace)
        self.created = True
        self.checkpoint("isolated local namespace created", context=self.options.context, namespace=self.namespace)
        self.kubectl("label", "namespace", self.namespace, "cbt.meldtech.org/drill=p722-local")
        self.apply("Secret", "credentials", stringData=self.credentials)
        self.record("environment", {"context": self.options.context, "namespace": self.namespace,
                    "server": server, "images": IMAGES, "started": self.started,
                    "revision": command(["git", "-C", str(ROOT), "rev-parse", "HEAD"]).strip(),
                    "workingTree": "uncommitted", "artifactSha256": hashlib.sha256(self.jar.read_bytes()).hexdigest()})
        self.workload("postgres", IMAGES["postgres"], 5432,
                      env=[self.secret_env("POSTGRES_PASSWORD", "postgres"), {"name": "POSTGRES_DB", "value": "p722"}])
        self.workload("rabbitmq", IMAGES["rabbitmq"], 5672,
                      env=[{"name": "RABBITMQ_DEFAULT_USER", "value": "p722"}, self.secret_env("RABBITMQ_DEFAULT_PASS", "broker")])
        wait_for("database ready", lambda: self.ready("postgres"))
        self.psql((ROOT / "src/main/resources/db/provisioning/V1__create_migration_role.sql").read_text())
        self.set_role_password("app_migrator", "migrator")
        migrator = self.fixture_pod("migrator", "--migrate-only", [
            {"name": "CBT_MIGRATION_JDBC_URL", "value": "jdbc:postgresql://postgres:5432/p722"},
            {"name": "CBT_MIGRATION_USERNAME", "value": "app_migrator"},
            {"name": "CBT_MIGRATION_CLASSIFICATION", "value": "EXPAND"},
            self.secret_env("CBT_DATABASE_ROLES_APP_MIGRATOR_PASSWORD", "migrator")])
        self.kubectl("exec", migrator, "--", "touch", "/application/ready")
        wait_for("migration completion", lambda: self.migration_complete(migrator))
        self.checkpoint("production migrations applied to isolated database")
        self.set_role_password("app_worker", "worker")
        self.psql("UPDATE platform.sweep_registry SET enabled=true, default_interval_ms=5000 WHERE sweep_name='outbox-backlog';\n"
                  "UPDATE platform.sweep_registry SET enabled=true WHERE sweep_name='outbox-relay';")
        wait_for("broker ready", lambda: self.ready("rabbitmq"))
        receiver = self.fixture_pod("receiver", "--receiver", [])
        self.kubectl("exec", receiver, "--", "touch", "/application/ready")
        worker = self.fixture_pod("worker", "--spring.config.location=classpath:/drill.properties", [
            self.secret_env("DRILL_WORKER_PASSWORD", "worker"), self.secret_env("DRILL_BROKER_PASSWORD", "broker"),
            self.secret_env("DRILL_METRICS_PASSWORD", "metrics")])
        self.kubectl("exec", worker, "--", "touch", "/application/ready")
        wait_for("worker management ready", lambda: self.ready("worker"))
        wait_for("receiver ready", lambda: self.ready("receiver"))
        self.forward("worker", 9091)
        self.forward("receiver", 8080)
        self.monitoring()

    def monitoring(self):
        runbook = f"http://127.0.0.1:{self.ports['receiver']}/runbook"
        rule = (ROOT / "config/alerting/outbox-backlog.rules.yml").read_text().replace("docs/runbooks/outbox-backlog.md", runbook)
        self.config("rules", {"outbox.yml": rule})
        prometheus = {"global": {"scrape_interval": "5s", "evaluation_interval": "5s"},
                      "rule_files": ["/rules/outbox.yml"], "alerting": {"alertmanagers": [
                          {"static_configs": [{"targets": ["alertmanager:9093"]}]}]},
                      "scrape_configs": [{"job_name": "p722-worker", "metrics_path": "/prometheus",
                          "basic_auth": {"username": "metrics", "password_file": "/credentials/metrics"},
                          "static_configs": [{"targets": ["worker:9091"], "labels": {
                              "cluster": "local-kind", "namespace": self.namespace}}]}]}
        alertmanager = {"route": {"receiver": "local-p2", "group_by": ["alertname", "cluster", "namespace", "job"],
                                 "group_wait": "1s", "group_interval": "5s", "repeat_interval": "1m"},
                        "receivers": [{"name": "local-p2", "webhook_configs": [
                            {"url": "http://receiver:8080/", "send_resolved": True}]}]}
        self.config("prometheus", {"prometheus.yml": json.dumps(prometheus)})
        self.config("alertmanager", {"alertmanager.yml": json.dumps(alertmanager)})
        self.workload("prometheus", IMAGES["prometheus"], 9090,
                      args=["--config.file=/config/prometheus.yml", "--storage.tsdb.path=/prometheus", "--storage.tsdb.retention.time=1h"],
                      mounts=[{"name": name, "mountPath": path, "readOnly": True} for name, path in
                              [("config", "/config"), ("rules", "/rules"), ("credentials", "/credentials")]],
                      volumes=[{"name": "config", "configMap": {"name": "prometheus"}},
                               {"name": "rules", "configMap": {"name": "rules"}},
                               {"name": "credentials", "secret": {"secretName": "credentials", "items": [{"key": "metrics", "path": "metrics"}]}}])
        self.workload("alertmanager", IMAGES["alertmanager"], 9093,
                      args=["--config.file=/config/alertmanager.yml", "--storage.path=/alertmanager", "--cluster.listen-address="],
                      mounts=[{"name": "config", "mountPath": "/config", "readOnly": True}],
                      volumes=[{"name": "config", "configMap": {"name": "alertmanager"}}])
        dashboard = json.loads((ROOT / "config/observability/outbox-backlog-dashboard.json").read_text()
                               .replace("${DS_PROMETHEUS}", "p722"))
        dashboard.pop("__inputs", None)
        for variable in dashboard["templating"]["list"]:
            value = {"cluster": "local-kind", "namespace": self.namespace, "job": "p722-worker", "runbook_url": runbook}[variable["name"]]
            variable["current"] = {"text": value, "value": value}
        self.config("dashboard", {"outbox.json": json.dumps(dashboard)})
        self.config("grafana", {"datasource.yml": json.dumps({"apiVersion": 1, "datasources": [
            {"name": "P722", "uid": "p722", "type": "prometheus", "access": "proxy", "url": "http://prometheus:9090", "isDefault": True}]}),
            "dashboard.yml": json.dumps({"apiVersion": 1, "providers": [{"name": "outbox", "type": "file",
                "options": {"path": "/dashboards"}}]})})
        self.workload("grafana", IMAGES["grafana"], 3000,
                      env=[self.secret_env("GF_SECURITY_ADMIN_PASSWORD", "grafana"), {"name": "GF_USERS_ALLOW_SIGN_UP", "value": "false"}],
                      mounts=[{"name": "config", "mountPath": "/etc/grafana/provisioning/datasources/datasource.yml", "subPath": "datasource.yml"},
                              {"name": "config", "mountPath": "/etc/grafana/provisioning/dashboards/dashboard.yml", "subPath": "dashboard.yml"},
                              {"name": "dashboard", "mountPath": "/dashboards"}],
                      volumes=[{"name": "config", "configMap": {"name": "grafana"}},
                               {"name": "dashboard", "configMap": {"name": "dashboard"}}])
        for name, port in [("prometheus", 9090), ("alertmanager", 9093), ("grafana", 3000)]:
            wait_for(name + " ready", lambda name=name: self.ready(name))
            self.forward(name, port)
        self.record("rendered-monitoring", {"prometheus": prometheus, "alertmanager": alertmanager, "rule": rule})
        wait_for("fresh baseline", lambda: self.scalar("outbox_backlog_sample_fresh") == 1)
        self.record("installed-dashboard", self.http("grafana", "/api/dashboards/uid/outbox-backlog",
                                                    credentials=("admin", self.credentials["grafana"])))

    def seed(self, count):
        self.psql(f"""
            BEGIN;
            SET LOCAL ROLE app_delivery;
            SELECT set_config('app.tenant_id', '00000000-0000-0000-0000-000000000722', true);
            WITH events AS (SELECT gen_random_uuid() AS id FROM generate_series(1, {count}))
            INSERT INTO outbox.outbox_event
                (outbox_event_id, tenant_id, aggregate_type, aggregate_id, event_type, payload,
                 correlation_id, occurred_at)
            SELECT id, '00000000-0000-0000-0000-000000000722', 'Reference', id::text,
                   'platform.ReferenceEvent.v1',
                   jsonb_build_object('eventType','platform.ReferenceEvent.v1','referenceId',id::text,'revision',1),
                   '01ARZ3NDEKTSV4RRFFQ69G5FAV', now() FROM events;
            COMMIT;
        """)

    def control(self, hold):
        self.http("worker", "/drill", {"hold": hold}, ("metrics", self.credentials["metrics"]))

    def receipt(self, after, status, started_at=None):
        receipts = self.http("receiver", "/")
        return next((receipt for receipt in receipts[after:] if receipt["notification"]["status"] == status
                     and any(alert["labels"].get("alertname") == "OutboxBacklog" and
                             alert["labels"].get("severity") == "p2" and
                             alert["labels"].get("namespace") == self.namespace and
                             (started_at is None or alert["startsAt"] == started_at)
                             for alert in receipt["notification"].get("alerts", []))), None)

    def snapshot(self, name):
        queries = {
            "depth": 'outbox_backlog_depth{state="PENDING"}', "age": "outbox_oldest_pending_age_seconds",
            "fresh": "outbox_backlog_sample_fresh", "scrape": 'up{job="p722-worker"}',
            "alert": 'ALERTS{alertname="OutboxBacklog",alertstate="firing"}',
        }
        snapshot = {"at": utc(), "queries": {key: self.query(value) for key, value in queries.items()},
                    "grafana": {key: self.query(value, grafana=True) for key, value in queries.items() if key != "alert"},
                    "database": self.psql("SELECT state, count(*) FROM outbox.outbox_event GROUP BY state ORDER BY state;"),
                    "pendingAge": self.psql("SELECT count(*), COALESCE(extract(epoch FROM now()-min(created_at)),0) FROM outbox.outbox_event WHERE state='PENDING';")}
        self.record(name, snapshot)
        return snapshot

    def run(self):
        self.jar = next((ROOT / "build/libs").glob("*-monitoring-drill.jar"))
        self.setup()
        require("OutboxBacklog" in self.http("receiver", "/runbook"), "Runbook is unavailable")
        require(self.scalar('outbox_backlog_depth{state="PENDING"}') == 0, "Baseline backlog is not empty")
        require(not self.query('ALERTS{alertname="OutboxBacklog",alertstate="firing"}'), "Unexpected baseline alert")
        self.snapshot("baseline")
        self.checkpoint("baseline verified")
        delivered = 0
        for scenario, count, deadline in [("depth", 5001, 90), ("age", 1, 390)]:
            self.control(True)
            after = len(self.http("receiver", "/"))
            self.seed(count)
            self.checkpoint(scenario + " backlog committed", count=count)
            firing = wait_for(scenario + " P2 delivery", lambda: self.receipt(after, "firing"), deadline)
            sample = self.snapshot(scenario + "-firing")
            depth = float(sample["queries"]["depth"][0]["value"][1])
            age = float(sample["queries"]["age"][0]["value"][1])
            require((depth > 5000 and age < 300) if scenario == "depth" else (depth == 1 and age > 300),
                    "Threshold branches were not independently exercised")
            self.checkpoint(scenario + " P2 received", receipt=firing["receivedAt"], depth=depth, age=age)
            started_at = next(alert["startsAt"] for alert in firing["notification"]["alerts"]
                              if alert["labels"].get("alertname") == "OutboxBacklog")
            self.control(False)
            resolved = wait_for(scenario + " recovery delivery", lambda: self.receipt(after, "resolved", started_at), 180)
            wait_for("fresh drained backlog", lambda: self.scalar('outbox_backlog_depth{state="PENDING"}') == 0
                     and self.scalar("outbox_backlog_sample_fresh") == 1)
            self.snapshot(scenario + "-recovery")
            delivered += count
            require(self.psql("SELECT count(*) FROM outbox.outbox_event WHERE state='PUBLISHED';").strip() == str(delivered),
                    "Committed events were not fully published")
            queue = self.kubectl("exec", "deployment/rabbitmq", "--", "rabbitmqctl", "list_queues", "name", "messages", "--quiet")
            require(f"p722-delivered\t{delivered}" in queue, "Broker delivery count differs from committed count")
            self.checkpoint(scenario + " recovery received", receipt=resolved["receivedAt"], published=delivered)
            self.record("notifications", self.http("receiver", "/"))
        self.psql("UPDATE platform.sweep_registry SET enabled=false WHERE sweep_name='outbox-backlog';")
        self.kubectl("exec", "deployment/worker", "--", "kill", "-TERM", "1")
        wait_for("disabled collector unknown", lambda: self.scalar("outbox_backlog_sample_fresh") == 0
                 and self.scalar('up{job="p722-worker"}') == 1)
        unknown = self.snapshot("collection-disabled")
        for metric in ("depth", "age"):
            samples = unknown["queries"][metric]
            require(samples and all(math.isnan(float(sample["value"][1])) for sample in samples),
                    "A disabled collector did not export an unknown " + metric)
        self.checkpoint("collection loss is unknown, not healthy")
        self.psql("UPDATE platform.sweep_registry SET enabled=true WHERE sweep_name='outbox-backlog';")
        self.kubectl("exec", "deployment/worker", "--", "kill", "-TERM", "1")
        wait_for("collection restored", lambda: self.scalar("outbox_backlog_sample_fresh") == 1)
        self.snapshot("collection-restored")
        self.record("notifications", self.http("receiver", "/"))
        self.record("installed-rules", self.http("prometheus", "/api/v1/rules"))
        self.record("targets", self.http("prometheus", "/api/v1/targets"))
        pods = json.loads(self.kubectl("get", "pods", "-o", "json"))["items"]
        self.record("image-identities", [{"pod": pod["metadata"]["name"], "containers": [
            {"name": item["name"], "image": item["image"], "imageID": item.get("imageID")}
            for item in pod.get("status", {}).get("containerStatuses", [])]} for pod in pods])
        self.record("result", {"result": "PASS_LOCAL_ONLY", "started": self.started, "finished": utc(),
                    "staging": "NOT_VERIFIED", "approval": "PENDING", "publishedAndDelivered": delivered,
                    "scope": "production sampler/runtime and relay adapters with a local-only assembly; not P8.5 production enablement"})
        self.checkpoint("local drill passed; staging remains unverified")

    def close(self):
        for process, logfile in self.forwards:
            process.terminate()
            process.wait(timeout=10)
            logfile.close()
        if self.created and not self.options.keep:
            self.kubectl("delete", "namespace", self.namespace, "--wait=false")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", default="docker-desktop")
    parser.add_argument("--namespace", default="p722-local")
    parser.add_argument("--output", required=True)
    parser.add_argument("--keep", action="store_true", help="Keep the owned local namespace for inspection")
    options = parser.parse_args()
    drill = Drill(options)
    try:
        drill.run()
    except Exception as error:
        drill.record("result", {"result": "FAIL", "finished": utc(), "reason": str(error)})
        raise
    finally:
        drill.close()


if __name__ == "__main__":
    main()