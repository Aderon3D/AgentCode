import json, sys, os, tempfile

review_file = sys.argv[1] if len(sys.argv) > 1 else "/tmp/review.md"
debug = "--debug" in sys.argv

content = open(review_file, encoding="utf-8").read()

lines = content.split("\n")
cleaned = []
in_details = 0
for line in lines:
    if "<details>" in line:
        in_details += 1
    elif "</details>" in line:
        in_details = max(0, in_details - 1)
    elif in_details == 0:
        cleaned.append(line)

findings = []
current = []
in_finding = False
for line in cleaned:
    if line.startswith("## Finding "):
        if current and in_finding:
            findings.append("\n".join(current))
        current = [line]
        in_finding = True
    elif in_finding:
        current.append(line)
if current and in_finding:
    findings.append("\n".join(current))

applied = 0
failed = 0
modified = []

SPLIT_MARKERS = ["# Fix:", "# Fix -", "# Fix —", "# Should be:", "# Change to:", "# Replace with:", "# Updated"]


def is_meta_comment(line):
    """Check if a line is an instructional comment (not actual code)."""
    stripped = line.strip()
    if not stripped.startswith("#"):
        return False
    # "# Line N:" references
    if stripped.startswith("# Line "):
        return True
    # "# In the X — do Y" descriptive comments
    if "change" in stripped.lower() or "add" in stripped.lower():
        return True
    if "fix" in stripped.lower() and "—" in stripped:
        return True
    if "should be" in stripped.lower():
        return True
    if stripped.startswith("# Fix"):
        return True
    return False


for i, sec in enumerate(findings):
    sev_line = ""
    for line in sec.split("\n")[:10]:
        if "severity:" in line.lower():
            sev_line = line.lower()
            break
    if "major" not in sev_line:
        continue

    filepath = None
    for line in sec.split("\n")[:10]:
        if "file:" in line.lower():
            start = line.find("`")
            if start >= 0:
                end = line.find("`", start + 1)
                if end > start:
                    filepath = line[start + 1 : end]
            break
    if not filepath:
        continue
    if ":" in filepath:
        parts = filepath.rsplit(":", 1)
        if parts[1].replace("-", "").isdigit():
            filepath = parts[0]

    code_blocks = []
    block_lines = []
    in_block = False
    for line in sec.split("\n"):
        if line.startswith("```") and not in_block:
            in_block = True
            block_lines = []
        elif line.startswith("```") and in_block:
            in_block = False
            code_blocks.append("\n".join(block_lines))
        elif in_block:
            block_lines.append(line)

    for block in code_blocks:
        before_text = None
        after_text = None

        for marker in SPLIT_MARKERS:
            # Find marker as start of a line
            search_from = 0
            while True:
                idx = block.find(marker, search_from)
                if idx < 0:
                    break
                # Check it's at start of line (or after only whitespace)
                line_start = block.rfind("\n", 0, idx) + 1
                prefix = block[line_start:idx]
                if prefix.strip() == "":
                    # Found at start of a line
                    before_text = block[:idx].strip()
                    after_start = idx + len(marker)
                    while after_start < len(block) and block[after_start] in "\n\r":
                        after_start += 1
                    after_text = block[after_start:].strip()
                    break
                search_from = idx + 1
            if before_text:
                break

        if not before_text or not after_text:
            continue
        if len(before_text) < 5 or len(after_text) < 3:
            continue

        # Strip meta-comment lines from before text
        before_lines = before_text.split("\n")
        before_lines = [l for l in before_lines if not is_meta_comment(l)]
        before_text = "\n".join(before_lines)

        if not before_text.strip():
            continue

        # Normalize indentation
        before_lines = before_text.split("\n")
        after_lines = after_text.split("\n")
        non_empty = [l for l in before_lines if l.strip()]
        if non_empty:
            min_indent = min(len(l) - len(l.lstrip()) for l in non_empty)
            before_text = "\n".join(
                l[min_indent:] if len(l) >= min_indent else l.lstrip()
                for l in before_lines
            )
            after_text = "\n".join(
                l[min_indent:] if len(l) >= min_indent else l.lstrip()
                for l in after_lines
            )

        if debug:
            print("Finding %d: %s" % (i + 1, filepath))
            print("  Before (%d chars):" % len(before_text))
            for line in before_text.split("\n")[:5]:
                print("    |%s|" % line)
            print("  After (%d chars):" % len(after_text))
            for line in after_text.split("\n")[:5]:
                print("    |%s|" % line)

        if not os.path.isfile(filepath):
            print("File not found: %s" % filepath, file=sys.stderr)
            failed += 1
            continue

        try:
            file_content = open(filepath, encoding="utf-8").read()
            if before_text not in file_content:
                print("Before text not found in %s" % filepath, file=sys.stderr)
                failed += 1
                continue

            new_content = file_content.replace(before_text, after_text, 1)
            fd, tmp = tempfile.mkstemp(dir=os.path.dirname(filepath) or ".")
            with os.fdopen(fd, "w") as tmpf:
                tmpf.write(new_content)
            os.replace(tmp, filepath)
            applied += 1
            modified.append(filepath)
            print("Applied fix to %s" % filepath)
            break
        except Exception as e:
            print("Error: %s: %s" % (filepath, e), file=sys.stderr)
            failed += 1

json.dump(
    {"applied": applied, "failed": failed, "modified": modified},
    open("/tmp/fix-result.json", "w"),
)
print("Applied=%d Failed=%d" % (applied, failed))
