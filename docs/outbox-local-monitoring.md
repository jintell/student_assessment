# Local P7.22 monitoring drill

This optional drill uses a dedicated namespace in an existing local Kubernetes
cluster. It is not a production deployment recipe or a replacement for CI or
staging acceptance. The current environment exposes its kind-based cluster as
`docker-desktop`; the `kind` CLI is not required. The script rejects a non-local
API endpoint and an existing namespace, and never changes the current context.

Prerequisites: JDK 21/Gradle wrapper, Python 3, kubectl, a running local cluster,
network access for the pinned images, and sufficient local resources for
PostgreSQL, RabbitMQ, the worker fixture, receiver, Prometheus, Alertmanager and
Grafana. Allow roughly ten minutes, including a genuine >300-second age trigger.

```bash
./gradlew monitoringDrillJar
python3 ci/verify-outbox-local-monitoring.py \
  --context docker-desktop --namespace p722-local \
  --output /tmp/p722-local-monitoring
```

The script generates ephemeral credentials, applies production migrations to a
new database, enables sampler/relay reservations only there, and copies the local
fixture JAR into pods using the pinned Java image. No image registry or cloud
service is needed. The `monitoringDrill` source set is excluded from the normal
Boot JAR, production manifests and production role inventory. Its relay assembly
uses existing claim/publication/broker adapters; it is not P8.5 production assembly.
The test receiver records only alert envelopes and receipt times, never credentials.

Each run generates independent 192-bit random credentials in memory for PostgreSQL,
the migration role, the worker role, RabbitMQ, the metrics/control endpoint and
Grafana. They are stored in the owned namespace's `credentials` Kubernetes Secret;
pods consume Secret references and Prometheus mounts only its metrics credential.
Secret creation uses stdin without a last-applied configuration annotation. Role
passwords also travel over stdin, and failures of credential-bearing commands
withhold subprocess output from logs and evidence files. Do not export the Secret
or commit generated credentials. No shared or production password is required.

The GitGuardian findings for commit `3f60cc1` reference this script, whose password
values are generated at runtime. If a run was retained with `--keep`, revoke its
credentials by deleting that owned namespace using the command below, waiting for
deletion to complete, then rerunning the drill. This removes the disposable database,
broker and monitoring services and loses their local data; it also stops that drill's
clients. Changing only the Kubernetes Secret does not rotate existing database roles,
broker users or Grafana accounts. No production consumer uses these generated values
in this repository. Any copies reused outside the drill require separate rotation
at their actual service. A new commit does not remove findings from the old commit;
rerun GitGuardian and review the reported matches before resolving the incidents.

Run the Docker-free credential regression checks with
`python3 -B ci/test-outbox-local-monitoring.py`.

The script reuses the committed alert rule and dashboard. Its rendered rule
changes only the runbook URL to the local receiver's runbook endpoint. Grafana
queries its provisioned Prometheus datasource for the dashboard's backlog,
age, freshness and scrape metrics in the isolated namespace.
Receiver firing and resolved callbacks, dashboard/query results, image IDs,
artifact checksum, target/rule configuration and UTC timestamps are retained in
the output directory. All service access is ClusterIP or localhost port-forward;
the worker scrape/control and Grafana require generated credentials. HTTP Basic
and CSRF-free control are exclusive to this fixture, not production security.

Collection and scrape/evaluation run every 5 seconds, samples expire after 15
seconds, and database queries time out at 2 seconds. Notification group wait is
1 second, group interval 5 seconds and repeat interval 1 minute. These are local
drill settings; production timing/cost approvals remain pending. Thresholds are
unchanged. A local authenticated control holds relay processing while the sampler
continues. Recovery uses normal confirmed RabbitMQ publication; it does not
rewrite event state or delete backlog. The fixture checks published and delivered
counts. Existing acceptance tests provide the separate consumer-idempotency proof.

The final collection-loss case disables only the sampler registry row and
restarts the fixture; a reachable scrape must show freshness zero and unknown
backlog, then recover after re-enable/restart. This is a local failure control,
not a production operator API. Application/database schemas and credentials are
ephemeral and isolated from existing namespaces.

By default the script removes its namespace and port-forwards on exit. `--keep`
retains the namespace for inspection; remove only that owned namespace afterwards:

```bash
kubectl --context docker-desktop delete namespace p722-local
```

Local evidence supports P7.22-LOCAL. The [proposed ADR](decisions/ADR-P722-001-local-monitoring-verification.md)
and [staging exception request](evidence/FEAT-PLAT-004/P7.22-staging-deferral.md)
still need named owner approvals. P7.22-STG remains mandatory before production.
