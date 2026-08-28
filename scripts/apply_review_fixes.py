import re, json, sys, os, tempfile

review_file = sys.argv[1] if len(sys.argv) > 1 else "/tmp/review.md"

try:
    content = open(review_file).read()
except:
    print("Cannot read review file", file=sys.stderr)
    json.dump({"applied": 0, "failed": 0, "modified": []}, open("/tmp/fix-result.json", "w"))
    sys.exit(0)

# Strip nested details blocks (old folded reviews)
content = re.sub(r"<details>.*?</details>", "", content, flags=re.DOTALL)

applied = 0
failed = 0
modified = []

# Pattern 1: ## Finding N: ... with **file:** and **severity:** fields
# followed by code block with # Before and # After or # Fix:
sections = re.split(r"(?=^## (?:Finding|Regression Risk))", content, flags=re.MULTILINE)

for sec in sections:
    if not sec.strip():
        continue

    # Check severity is Major
    sev_match = re.search(r"severity:\s*Major", sec, re.IGNORECASE)
    if not sev_match:
        continue

    # Extract file path
    file_match = re.search(r"\*\*file:\*\*\s*`?([^`\n]+)`?", sec)
    if not file_match:
        continue
    filepath = file_match.group(1).strip()

    # Extract line number (optional, for reference)
    line_match = re.search(r":(\d+)", filepath)
    if line_match:
        filepath = filepath[:filepath.rfind(":")]

    # Try to find Before/After or Before/Fix code blocks
    # Pattern: ```lang\n# Before\n...\n# After\n...\n``` or ```lang\n# Before\n...\n# Fix:\n...\n```
    before_text = None
    after_text = None

    # Look for code blocks with markers
    code_blocks = list(re.finditer(r"```(?:\w+)?\s*\n(.*?)```", sec, re.DOTALL))
    for i, block in enumerate(code_blocks):
        block_content = block.group(1)

        # Check if this block has # Before
        if "# Before" in block_content or "# before" in block_content.lower():
            # Extract text after # Before
            before_match = re.search(r"# [Bb]efore[^\n]*\n(.*?)(?=# [Aa]fter|# [Ff]ix|$)", block_content, re.DOTALL)
            if before_match:
                before_text = before_match.group(1).strip()

            # Look for # After or # Fix in same or next block
            after_match = re.search(r"# [Aa]fter[^\n]*\n(.*?)(?=$)", block_content, re.DOTALL)
            if not after_match:
                after_match = re.search(r"# [Ff]ix[^\n]*\n(.*?)(?=$)", block_content, re.DOTALL)
            if after_match:
                after_text = after_match.group(1).strip()

            # If no After in same block, check next block
            if not after_text and i + 1 < len(code_blocks):
                next_block = code_blocks[i + 1].group(1)
                after_match = re.search(r"# [Aa]fter[^\n]*\n(.*?)(?=$)", next_block, re.DOTALL)
                if not after_match:
                    after_match = re.search(r"# [Ff]ix[^\n]*\n(.*?)(?=$)", next_block, re.DOTALL)
                if after_match:
                    after_text = after_match.group(1).strip()
                elif "# Before" not in next_block:
                    # Next block might be the replacement without marker
                    after_text = next_block.strip()

    # Pattern 2: Look for "Concrete fix:" followed by code block
    if not before_text or not after_text:
        fix_section = re.search(r"(?:Concrete fix|Fix|Suggested fix)[:\s]*\n(.*?)(?=\n## |\n---|\Z)", sec, re.DOTALL)
        if fix_section:
            fix_blocks = list(re.finditer(r"```(?:\w+)?\s*\n(.*?)```", fix_section.group(1), re.DOTALL))
            if len(fix_blocks) >= 2:
                before_text = fix_blocks[0].group(1).strip()
                after_text = fix_blocks[1].group(1).strip()
            elif len(fix_blocks) == 1:
                # Single code block = the fix, need to find before from context
                after_text = fix_blocks[0].group(1).strip()

    # Pattern 3: Look for "Replace X with Y" patterns in text
    if not before_text or not after_text:
        replace_match = re.search(r"replace\s+`([^`]+)`\s+with\s+`([^`]+)`", sec, re.IGNORECASE)
        if replace_match:
            before_text = replace_match.group(1)
            after_text = replace_match.group(2)

    if not before_text or not after_text:
        print(f"Skip: no before/after found for {filepath}", file=sys.stderr)
        failed += 1
        continue

    # Apply the fix
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
        print(f"Error applying fix to {filepath}: {e}", file=sys.stderr)
        failed += 1

json.dump({"applied": applied, "failed": failed, "modified": modified}, open("/tmp/fix-result.json", "w"))
print(f"Applied={applied} Failed={failed}")
