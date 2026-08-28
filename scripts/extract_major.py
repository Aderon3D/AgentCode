import re, sys

review = open("/tmp/review.md").read()

# Strip nested details blocks (old folded reviews)
cleaned = re.sub(r"<details>.*?</details>", "", review, flags=re.DOTALL)

# Extract sections with Major severity
sections = re.split(r"(?=^## )", cleaned, flags=re.MULTILINE)
major_sections = []
for sec in sections:
    if re.search(r"severity:\s*Major", sec, re.IGNORECASE):
        major_sections.append(sec.strip())

if not major_sections:
    sys.stdout.write("No Major findings to fix.\n")
else:
    header = "Apply these code fixes to the repository files:\n\n"
    sys.stdout.write(header + "\n\n".join(major_sections) + "\n")
