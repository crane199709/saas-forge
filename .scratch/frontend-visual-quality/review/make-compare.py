"""生成 5 张关键页面的前后对比合成图：左 = main 基线（整改前），右 = 当前工作区基线（整改后）。

用法（仓库根或任意目录均可，需要 Pillow）：
    python3 .scratch/frontend-visual-quality/review/make-compare.py

"前" 直接取 `git show main:<基线路径>`，"后" 取工作区的同名文件，
因此对比材料只依赖 git 与工作区，不需要重新跑视觉测试。
"""

import io
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

REPO = Path(__file__).resolve().parents[3]
SHOTS = 'frontend/e2e/visual/__screenshots__'
OUT = Path(__file__).resolve().parent
# macOS 自带的中文字体；换平台时改成对应的 CJK 字体即可。
FONT = '/System/Library/Fonts/Hiragino Sans GB.ttc'

NAMES = [
    'login-tenant-desktop',
    'workbench-empty-desktop',
    'org-members-desktop',
    'roles-members-desktop',
    'platform-tenants-desktop',
]

LABEL_H = 64
PAD = 16
GAP = 20


def main() -> None:
    title_font = ImageFont.truetype(FONT, 22)
    label_font = ImageFont.truetype(FONT, 20)
    note_font = ImageFont.truetype(FONT, 16)

    for name in NAMES:
        raw = subprocess.run(
            ['git', 'show', f'main:{SHOTS}/{name}.png'],
            cwd=REPO,
            capture_output=True,
            check=True,
        ).stdout
        before = Image.open(io.BytesIO(raw)).convert('RGB')
        after = Image.open(REPO / SHOTS / f'{name}.png').convert('RGB')
        w, h = before.size

        canvas = Image.new('RGB', (PAD * 2 + w * 2 + GAP, PAD * 2 + LABEL_H + h + 22), '#ffffff')
        draw = ImageDraw.Draw(canvas)
        draw.text((PAD, PAD), name, fill='#1f2328', font=title_font)

        top = PAD + LABEL_H
        for index, (img, caption, tint) in enumerate(
            [
                (before, '前：main（整改前）', '#7a6a00'),
                (after, '后：frontend-visual-quality（整改后）', '#0b6b3a'),
            ]
        ):
            x = PAD + index * (w + GAP)
            draw.text((x, PAD + 34), caption, fill=tint, font=label_font)
            canvas.paste(img, (x, top))
            draw.rectangle([x - 1, top - 1, x + w, top + h], outline='#d0d7de', width=1)

        note = '合成图工具：macOS + Pillow；两图均为 1440×900，未缩放、未调色。基线只在 macOS 生成。'
        draw.text((PAD, top + h + 4), note, fill='#57606a', font=note_font)
        out = OUT / f'{name}-compare.png'
        canvas.save(out, optimize=True)
        print(out, canvas.size, out.stat().st_size // 1024, 'KB')


if __name__ == '__main__':
    main()
