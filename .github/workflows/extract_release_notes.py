import re
import sys
import os

def extract_latest_release_notes(changelog_path):
    if not os.path.exists(changelog_path):
        return "변경 사항은 CHANGELOG.md를 참조해 주세요."

    with open(changelog_path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # 첫 번째 ## [vX.X.X] 섹션부터 다음 --- 또는 파일 끝까지 추출
    # 패턴: ## [버전] - 날짜\n(내용)(다음 --- 혹은 끝)
    pattern = r"## \[(v.*?)\] - .*?\n(.*?)(?=\n---|\Z)"
    match = re.search(pattern, content, re.DOTALL)
    
    if match:
        version = match.group(1)
        notes = match.group(2).strip()
        return f"### 🚀 Release {version}\n\n{notes}"

    return "이번 릴리즈의 상세 내역은 CHANGELOG.md를 참조해 주세요."

if __name__ == "__main__":
    # 기본적으로 CHANGELOG.md를 읽음
    print(extract_latest_release_notes('CHANGELOG.md'))
