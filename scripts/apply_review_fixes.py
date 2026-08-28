import re, json, sys, os, tempfile

review_file = sys.argv[1] if len(sys.argv) > 1 else "/tmp/review.md"

try:
    content = open(review_file).read()
except:
    print("Cannot read review file", file=sys.stderr)
    json.dump({"applied": 0, "failed": 0, "modified": []}, open("/tmp/fix-result.json", "w"))
    sys.exit(0)

# Remove details blocks line by line (no regex on large content)
lines = content.split("\n")
cleaned_lines = []
in_details = 0
for line in lines:
    if "<details>" in line:
        in_details += 1
    elif "</details>" in line:
        in_details = max(0, in_details - 1)
    elif in_details == 0:
        cleaned_lines.append(line)
content = "\n".join(cleaned_lines)

applied = 0
failed = 0
modified = []

# Split into sections by ## headers
sections = re.split(r"(?=^## )", content, flags=re.MULTILINE)

for sec in sections:
    if not sec.strip():
        continue

    # Check severity is Major
    if not re.search(r"severity:\s*Major", sec, re.IGNORECASE):
        continue

    # Extract file path
    file_match = re.search(r"\*\*file:\*\*\s*`?([^`\n]+)`?", sec)
    if not file_match:
        continue
    filepath = file_match.group(1).strip()

    # Clean filepath (remove line numbers like :5)
    if ":" in filepath and filepath.rsplit(":", 1)[1].isdigit():
        filepath = filepath.rsplit(":", 1)[0]

    # Find code blocks
    blocks = list(re.finditer(r"```(\w+)?\s*\n(.*?)```", sec, re.DOTALL))

    before_text = None
    after_text = None

    for i, m in enumerate(blocks):
        block = m.group(2)

        # Look for # Before marker
        if re.search(r"# [Bb]efore", block):
            # Extract text after # Before
            bmatch = re.search(r"# [Bb]efore[^\n]*\n(.*?)(?=# [Aa]fter|# [Ff]ix:?\s*$|\Z)", block, re.DOTALL | re.MULTILINE)
            if bmatch:
                before_text = bmatch.group(1).strip()

            # Look for # After or # Fix in same block
            amatch = re.search(r"# [Aa]fter[^\n]*\n(.*?)$", block, re.DOTALL | re.MULTILINE)
            if not amatch:
                amatch = re.search(r"# [Ff]ix:?[^\n]*\n(.*?)$", block, re.DOTALL | re.MULTILINE)
            if amatch:
                after_text = amatch.group(1).strip()

            # If no after in same block, check next block
            if not after_text and i + 1 < len(blocks):
                next_block = blocks[i + 1].group(2)
                if not re.search(r"# [Bb]efore", next_block):
                    after_text = next_block.strip()
            break

    # Fallback: look for "Concrete fix:" then two code blocks
    if not before_text or not after_text:
        fix_idx = sec.find("Concrete fix")
        if fix_idx == -1:
            fix_idx = sec.find("Suggested fix")
        if fix_idx >= 0:
            after_fix = sec[fix_idx:]
            fix_blocks = list(re.finditer(r"```(\w+)?\s*\n(.*?)```", after_fix, re.DOTALL))
            if len(fix_blocks) >= 2:
                before_text = fix_blocks[0].group(2).strip()
                after_text = fix_blocks[1].group(2).strip()
            elif len(fix_blocks) == 1:
                after_text = fix_blocks[0].group(2).strip()

    # Fallback: "Replace X with Y"
    if not before_text or not after_text:
        rmatch = re.search(r"replace\s+`([^`]+)`\s+with\s+`([^`]+)`", sec, re.IGNORECASE)
        if rmatch:
            before_text = rmatch.group(1)
            after_text = rmatch.group(2)

    if not before_text or not after_text:
        print(f"Skip: no before/after for {filepath}", file=sys.stderr)
        failed += 1
        continue

    if not os.path.isfile(filepath):
        print(f"File not found: {filepath}", file=sys.stderr)
        failed += 1
        continue

    try:
        file_content = open(filepath).read()
        if before_text not in file_content:
            print(f"Before text not found in {filepath}", file=sys.stderr)
            failed += 1
            continue
        new_content = file_content.replace(before_text, after_text, 1)
        fd, tmp = tempfile.mkstemp(dir=os.path.dirname(filepath) or ".")
        with os.fdopen(fd, "w") as tmpf:
            tmpf.write(new_content)
        os.replace(tmp, filepath)
        applied += 1
        modified.append(filepath)
        print(f"Applied fix to {filepath}")
    except Exception as e:
        print(f"Error: {filepath}: {e}", file=sys.stderr)
        failed += 1

json.dump({"applied": applied, "failed": failed, "modified": modified}, open("/tmp/fix-result.json", "w"))
print(f"Applied={applied} Failed={failed}")
