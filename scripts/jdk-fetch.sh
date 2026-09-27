#!/usr/bin/env bash
#
# jdk-fetch.sh — fetch one page with the JVM's HTTP client and the importer's User-Agent.
#
# Some venue hosts sit behind a bot rule that refuses curl and serves the JVM, which is what the
# importer runs on (MaayaWebsiteImporter's KDoc says so for maaya.de). A curl comparison of such a
# host reports NOT COMPARABLE for a page the importer reads every day (#1955), so /plausibility-check
# fetches those hosts this way. One request, redirects followed, no retry.
#
# Usage: scripts/jdk-fetch.sh <url> <output-file>
#
# Needs `java` 11 or later on PATH; the Actions runner has one. Writes the body to <output-file> and
# prints the HTTP status. Exit code: 0 on any HTTP answer, 1 on a network failure, 2 on bad arguments.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

[ $# -eq 2 ] || {
    echo "jdk-fetch: needs a URL and an output file; see --help" >&2
    exit 2
}

source_dir=$(mktemp -d)
trap 'rm -rf "$source_dir"' EXIT
cat >"$source_dir/JdkFetch.java" <<'EOF'
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

public class JdkFetch {
    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(20))
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(args[0]))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Mozilla/5.0 (compatible; EventJunkie/1.0; +https://github.com/enorm-labs/event-junkie)")
            .build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(Path.of(args[1])));
        System.out.println(response.statusCode());
    }
}
EOF

java "$source_dir/JdkFetch.java" "$1" "$2" || exit 1
