#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""按当前 dev 架构绘制三张稳定插图，并导出 SVG 与 PNG。"""

import subprocess
from pathlib import Path

OUT = Path(__file__).resolve().parent / "figures"
FONT = "WenQuanYi Micro Hei, sans-serif"
TEXT = "#263238"
MUTED = "#4E5B63"


def esc(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def box(x, y, w, h, lines, fill, stroke, size=18, weight="400", anchor="middle"):
    parts = [
        f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="8" '
        f'fill="{fill}" stroke="{stroke}" stroke-width="2"/>'
    ]
    line_h = size + 8
    total = line_h * len(lines)
    start = y + (h - total) / 2 + size
    cx = x + w / 2 if anchor == "middle" else x + 16
    for i, line in enumerate(lines):
        parts.append(
            f'<text x="{cx}" y="{start + i * line_h:.1f}" text-anchor="{anchor}" '
            f'font-family="{FONT}" font-size="{size}" font-weight="{weight}" fill="{TEXT}">'
            f"{esc(line)}</text>"
        )
    return "\n".join(parts)


def label(x, y, text, size=15, fill=MUTED, anchor="middle"):
    return (
        f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-family="{FONT}" '
        f'font-size="{size}" fill="{fill}">{esc(text)}</text>'
    )


def arrow(x1, y1, x2, y2, color="#1A703A", dash=None):
    dash_attr = f' stroke-dasharray="{dash}"' if dash else ""
    return (
        f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{color}" '
        f'stroke-width="2"{dash_attr} marker-end="url(#arrow-{color[1:]})"/>'
    )


def svg_wrap(width, height, body: str) -> str:
    colors = ["1A703A", "245E8D", "9B6100", "65737B"]
    markers = []
    for color in colors:
        markers.append(
            f'<marker id="arrow-{color}" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">'
            f'<path d="M0,0 L8,4 L0,8 Z" fill="#{color}"/></marker>'
        )
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
  <rect width="100%" height="100%" fill="#FFFFFF"/>
  <defs>
    {''.join(markers)}
  </defs>
  {body}
</svg>
'''


def architecture() -> str:
    w, h = 1100, 910
    parts = []
    parts.append(box(310, 24, 480, 78, ["PC 浏览器", "Vue 3 · Ant Design Vue · 浅色工作台"], "#F5F6F7", "#65737B", 18, "600"))
    parts.append(arrow(550, 102, 550, 138))
    parts.append(label(550, 128, "同源 HTTP", 14, "#1A703A"))
    parts.append(
        f'<rect x="36" y="146" width="1028" height="420" rx="12" fill="#FFFFFF" stroke="#1A703A" stroke-width="2"/>'
    )
    parts.append(label(550, 176, "Spring Boot 3.3.5 · Java 17 · 单进程 · JAR 内 static 前端 · 容器端口 8080", 16, "#1A703A"))
    parts.append(box(
        70, 196, 960, 340,
        [
            "/api/v2  审查、Agent 与报告",
            "配置 agent.datasource.url 后注册",
            "",
            "专项：问题审查、设计模式、耦合度",
            "Agent：只读工具 tree / find_files / search_code / read_code / read_diff",
            "完成动作 submit_result",
            "报告：冻结来源后生成",
            "",
            "Flyway  classpath:db/agent-migration  V1–V6",
            "历史表 agent_schema_history，身份版本 1",
        ],
        "#EEF5EF", "#1A703A", 16,
    ))
    parts.append(arrow(220, 566, 220, 612, "#245E8D", "6 4"))
    parts.append(arrow(550, 566, 550, 612, "#1A703A"))
    parts.append(arrow(880, 566, 880, 612, "#1A703A"))
    parts.append(box(
        36, 620, 330, 250,
        [
            "MySQL 8.0.36",
            "库 ai_code_review_agent",
            "由 agent.datasource 连接",
            "库名须与 expected-database 一致",
            "历史表 agent_schema_history",
            "身份版本 1",
        ],
        "#EFF5FA", "#245E8D", 16,
    ))
    parts.append(box(
        386, 620, 330, 250,
        [
            "Git 宿主",
            "GitHub REST",
            "Gitea 1.16.1",
            "REST 与 .diff",
            "JGit bare 镜像",
            "供三点差异与检索",
        ],
        "#F5F6F7", "#65737B", 16,
    ))
    parts.append(box(
        736, 620, 328, 250,
        [
            "内网 AI 网关",
            "OpenAI Chat 兼容",
            "POST …/chat/completions",
            "Bearer 令牌",
            "专项与报告走 JSON",
            "Agent 携带工具定义",
        ],
        "#F5F6F7", "#65737B", 16,
    ))
    return svg_wrap(w, h, "\n".join(parts))


def sequence() -> str:
    w, h = 1100, 980
    parts = []
    parts.append(box(180, 20, 740, 64, ["浏览器提交范围、策略或调查要求"], "#F5F6F7", "#65737B", 18, "600"))
    parts.append(arrow(550, 84, 550, 112))
    parts.append(box(140, 118, 820, 88, ["POST /api/v2/review-tasks 或 /api/v2/agent-tasks", "校验项目与模型，冻结输入和配置快照，写入 QUEUED，返回 202"], "#EEF5EF", "#1A703A", 16))
    parts.append(arrow(550, 206, 550, 234))
    parts.append(box(140, 240, 820, 64, ["执行器将任务置为 PREPARING，随后 RUNNING"], "#EEF5EF", "#1A703A", 18))
    parts.append(arrow(360, 304, 250, 340))
    parts.append(arrow(740, 304, 850, 340))
    parts.append(box(
        36, 348, 490, 280,
        [
            "专项审查",
            "按文件或提交差异制备单元",
            "超限则拆分；无法拆分则该目标失败",
            "JSON 模式调用模型",
            "问题、模式或耦合观察写入结果",
            "反馈在结果发布后单独保存",
        ],
        "#EEF5EF", "#1A703A", 16,
    ))
    parts.append(box(
        574, 348, 490, 280,
        [
            "Agent 分析",
            "历史原样回放，不压缩上下文",
            "只读工具，每轮最多 4 个",
            "只有 submit_result 才发布结果",
            "命中预算即停止，不追加总结",
            "不提供中断后的会话恢复",
        ],
        "#EEF5EF", "#1A703A", 16,
    ))
    parts.append(arrow(281, 628, 420, 668))
    parts.append(arrow(819, 628, 680, 668))
    parts.append(box(
        140, 676, 820, 110,
        [
            "浏览器轮询至终态：专项详情约 3 秒，Agent 详情约 2 秒",
            "审查中心列表不自动轮询，需手动刷新",
            "进程重启时，尚未结束的 QUEUED / RUNNING 标为 FAILED，原因 INTERRUPTED",
        ],
        "#EFF5FA", "#245E8D", 16,
    ))
    parts.append(label(550, 830, "人工反馈、报告冻结与下载发生在任务到达终态之后，不改写模型原始输出。", 15))
    return svg_wrap(w, h, "\n".join(parts))


def deployment() -> str:
    w, h = 1100, 980
    parts = []
    parts.append(
        f'<rect x="36" y="24" width="1028" height="150" rx="12" fill="#F5F6F7" stroke="#65737B" stroke-width="2" stroke-dasharray="8 5"/>'
    )
    parts.append(label(550, 52, "开发与 CI 构建区", 16, "#65737B"))
    parts.append(box(70, 68, 960, 84, ["GitHub Actions：Node 22 / pnpm 10.18.2 构建前端，Java 17 / Maven 打包", "导出 linux/amd64 应用镜像，并拉取 mysql:8.0.36，组成离线包"], "#FFFFFF", "#65737B", 16))
    parts.append(arrow(550, 174, 550, 214, "#9B6100", "8 5"))
    parts.append(box(250, 220, 600, 72, ["离线包 codereview-offline-<版本>.tar.gz", "镜像、Compose、脚本、配置模板、源码快照、校验和"], "#FFFFFF", "#9B6100", 16))
    parts.append(arrow(550, 292, 550, 332, "#9B6100", "8 5"))
    parts.append(
        f'<rect x="36" y="340" width="1028" height="470" rx="12" fill="#FFFFFF" stroke="#1A703A" stroke-width="2"/>'
    )
    parts.append(label(550, 368, "内网运行区 · Docker Compose 项目 codereview", 16, "#1A703A"))
    parts.append(box(56, 388, 470, 180, ["app 容器", "JRE 17 + app.jar + static 前端", "宿主 APP_PORT 默认 18080 → 8080", "只读挂载 config/application.yml", "持久化 data/git-mirrors"], "#EEF5EF", "#1A703A", 16))
    parts.append(box(560, 388, 470, 180, ["mysql 容器  8.0.36", "只在网络内提供 mysql:3306", "不发布宿主端口", "库 ai_code_review_agent", "由 agent.datasource 连接"], "#EFF5FA", "#245E8D", 16))
    parts.append(arrow(526, 470, 560, 470, "#245E8D", "6 4"))
    parts.append(label(543, 458, "JDBC", 14, "#245E8D"))
    parts.append(box(56, 590, 470, 190, ["内网 PC 浏览器", "http://<目标机内网IP>:18080", "首页为审查中心", "须配置 agent.datasource", "后业务接口才可用"], "#F5F6F7", "#65737B", 16))
    parts.append(box(560, 590, 470, 190, ["经宿主出站访问", "内网 Gitea：REST / .diff / Git", "AI 网关：/chat/completions", "Agent 检索使用镜像目录中的 git", "镜像路径不宜过深"], "#F5F6F7", "#65737B", 16))
    parts.append(arrow(291, 590, 291, 568, "#1A703A"))
    parts.append(label(550, 850, "镜像回退不撤销 agent_schema_history。应用配置须包含 agent.datasource。", 15))
    parts.append(label(550, 920, "交付模板默认不写入 agent.datasource。启用工作台前须创建应用库并重启应用。", 15))
    return svg_wrap(w, h, "\n".join(parts))


def render_png(svg_path: Path, png_path: Path, width: int, height: int) -> None:
    del height
    subprocess.run(
        ["rsvg-convert", "-w", str(width * 2), "-o", str(png_path), str(svg_path)],
        check=True,
    )


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    specs = {
        "architecture": (architecture(), 1100, 910),
        "review-sequence": (sequence(), 1100, 980),
        "deployment": (deployment(), 1100, 980),
    }
    for name, (svg, width, height) in specs.items():
        svg_path = OUT / f"{name}.svg"
        svg_path.write_text(svg, encoding="utf-8")
        render_png(svg_path, OUT / f"{name}.png", width, height)
        print(name, (OUT / f"{name}.png").stat().st_size)


if __name__ == "__main__":
    main()
