package com.agent.code.opencode

// Guard for the autonomous run path. opencode executes its own tools under
// --auto, so enforcement must live inside opencode: a local plugin's
// tool.execute.before is the only hook that fires under --auto (verified on
// the shipped 1.18.27 binary — permission.ask never fires, and --pure blocks
// every plugin including local paths, so buildRunArgs dropped it). The plugin
// and its config are written under HOME=installDir (elfloader) so the global
// config applies without touching workspace configs (which stay plugin-free
// to avoid the npm/arborist startup stall, r25 log 27).
internal object AgentGuard {

    fun configJson(installDir: String): String = """
        {
          "${'$'}schema": "https://opencode.ai/config.json",
          "plugin": ["$installDir/agent-guard.mjs"]
        }
    """.trimIndent()

    // Same text as the node-verified guard (35-assert check): audit every
    // tool call, deny install-dir access (auth.json/binary/guard tampering),
    // deny destructive bash and credential-path references, confine
    // write/edit AND bash write targets (redirection/write verbs) to the
    // workspace — bash redirection bypasses filePath checks otherwise.
    // Reads stay open: Android's UID sandbox bounds disclosure, and
    // installDir is matched verbatim. Throws surface as tool errors the
    // model sees and can adapt to; under --auto an explicit deny is never
    // auto-approved. ponytail ceiling: interpreter-internal writes
    // (python -c mid-token paths) and curl -o escapes need AST parsing
    // (bash-guard) if they ever matter.
    fun pluginJs(installDir: String): String = """
        import { appendFileSync } from "node:fs";
        const INSTALL = "$installDir";
        const AUDIT = INSTALL + "/agent-audit.log";
        // Credential/app-integrity references: the agent has no legitimate reason to
        // touch these, and [$]HOME tricks would otherwise hide the real path.
        const SECRETS = /auth\.json|[.]config[/]opencode|[.]npmrc|agent-guard|[.]loader-v2|[$]HOME|\bHOME=/;
        // Destructive / irreversible commands, denied regardless of context.
        const EVIL = [
          /\brm\s+(-[a-zA-Z]+\s+)*-[a-zA-Z]*r/,
          /\bmkfs|\bdd\s+if=|:\(\)\{/,
          /\b(shutdown|reboot|halt|poweroff)\b/,
          /\bchmod\s+(-[a-zA-Z]+\s+)*[0-7]{3,4}\s+\/(\s|$)/,
          /(curl|wget)\b[^;&|]*\|\s*(ba|z|da)?sh/,
          /\bhistory\s+-c|[>&]\s*\/etc\/(passwd|shadow)/,
        ];
        // Commands whose arguments can be write targets. Reads of these verbs are
        // also denied when they name absolute paths — the model can fall back to
        // cat/ls, which stay allowed.
        const WRITE_VERBS = /(?:^|\s)(?:cp|mv|tee|mkdir|touch|ln|install|rm|sed|awk|dd|git|python3?|node|perl|ruby|sh|bash)(?:\s|$)/;
        export default async ({ directory }) => {
          const deny = (msg) => {
            throw new Error("agent-guard: " + msg);
          };
          const audit = (tool, args) => {
            try {
              const blob = JSON.stringify(args) || "";
              appendFileSync(AUDIT, Date.now() + " " + tool + " " + blob.slice(0, 500) + "\n");
            } catch {}
          };
          // A write target escapes the workspace unless it is the workspace itself,
          // a /dev/null-style sink, or stays inside. Reads are not checked here.
          const badTarget = (t) =>
            t.startsWith("~") ||
            /(^|\/)\.\.(\/|$)/.test(t) ||
            (t.startsWith("/") &&
              !(t === directory || t.startsWith(directory + "/") || /^\/dev\/(null|stdout|stderr)$/.test(t)));
          return {
            "tool.execute.before": async (inp, out) => {
              const args = out.args || {};
              audit(inp.tool, args);
              const blob = JSON.stringify(args) || "";
              if (blob.includes(INSTALL)) deny("install dir is off-limits");
              if (inp.tool === "bash") {
                const cmd = String(args.command || "");
                if (SECRETS.test(cmd)) deny("credential/config path in command");
                if (/\bgit\b[^;&|]*--global/.test(cmd)) deny("git --global writes outside workspace");
                for (const re of EVIL) {
                  if (re.test(cmd)) deny("destructive command: " + cmd.slice(0, 80));
                }
                // bash bypasses filePath checks via > redirection — confine targets.
                const redir = /(?:^|[^<>])>{1,2}\s*([^\s;|&]+)/g;
                let m;
                while ((m = redir.exec(cmd)) !== null) {
                  const t = m[1].replace(/^["']+|["']+$/g, "");
                  if (badTarget(t)) deny("write outside workspace: " + t);
                }
                if (WRITE_VERBS.test(cmd)) {
                  // Absolute paths anywhere in a write-capable command (URLs and
                  // sed s/a/b/ delimiters are excluded by the lookbehind).
                  const absRe = /(?<![:\w/])\/[^\s;|&'"<>]+/g;
                  let a;
                  while ((a = absRe.exec(cmd)) !== null) {
                    if (badTarget(a[0])) deny("write outside workspace: " + a[0].slice(0, 80));
                  }
                  for (const raw of cmd.split(/\s+/)) {
                    const t = raw.replace(/^["']+|["']+$/g, "");
                    if (t.startsWith("~") || /(^|\/)\.\.(\/|$)/.test(t)) {
                      deny("write outside workspace: " + t.slice(0, 80));
                    }
                  }
                }
              }
              const fp = args.filePath;
              // Same target rules as bash: relative paths resolve inside the
              // child's cwd (the workspace); "..", "~" and absolute paths
              // outside it are denied. A bare prefix test let both
              // "workspace/../opencode/x" and "/…/workspace-evil" through.
              if ((inp.tool === "write" || inp.tool === "edit" || inp.tool === "patch") &&
                  fp && badTarget(String(fp).replace(/^["']+|["']+$/g, ""))) {
                deny("write outside workspace: " + fp);
              }
            },
          };
        };
    """.trimIndent()
}
