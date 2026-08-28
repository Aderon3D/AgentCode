import json, sys, os, tempfile

result_file = sys.argv[1]
try:
    raw = open(result_file).read()
except:
    print("Cannot read result file", file=sys.stderr)
    sys.exit(0)

applied = 0
failed = 0
modified = []

for line in raw.strip().split("\n"):
    line = line.strip()
    if not line or not line.startswith("{"):
        continue
    try:
        fix = json.loads(line)
        fpath = fix["file"]
        before = fix["before"]
        after = fix["after"]
    except (json.JSONDecodeError, KeyError) as e:
        print(f"Skip line: {e}", file=sys.stderr)
        failed += 1
        continue

    if not os.path.isfile(fpath):
        print(f"File not found: {fpath}", file=sys.stderr)
        failed += 1
        continue

    try:
        content = open(fpath).read()
        if before not in content:
            print(f"Before text not found in {fpath}", file=sys.stderr)
            failed += 1
            continue
        new_content = content.replace(before, after, 1)
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(fpath) or ".")
        with os.fdopen(fd, "w") as tmpf:
            tmpf.write(new_content)
        os.replace(tmp, fpath)
        applied += 1
        modified.append(fpath)
        print(f"Applied fix to {fpath}")
    except Exception as e:
        print(f"Error applying fix to {fpath}: {e}", file=sys.stderr)
        failed += 1

result = {"applied": applied, "failed": failed, "modified": modified}
json.dump(result, open("/tmp/fix-result.json", "w"))
print(f"Applied={applied} Failed={failed}")
