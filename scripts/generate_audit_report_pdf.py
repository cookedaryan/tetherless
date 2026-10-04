import os
import re
import subprocess
import sys
import html

def format_inline_md(text):
    # Process inline code first with placeholder
    code_spans = []
    def code_sub(m):
        code_spans.append(m.group(1))
        return f"__CODE_SPAN_{len(code_spans)-1}__"
    
    text = re.sub(r'`([^`]+)`', code_sub, text)
    
    # Escape HTML
    text = html.escape(text)
    
    # Links: [text](url)
    text = re.sub(r'\[([^\]]+)\]\(([^)]+)\)', r'<a href="\2" class="doc-link">\1</a>', text)
    
    # Bold: **text**
    text = re.sub(r'\*\*([^*]+)\*\*', r'<strong>\1</strong>', text)
    
    # Italic: *text*
    text = re.sub(r'\*([^*]+)\*', r'<em>\1</em>', text)
    
    # Math inline: $...$
    text = re.sub(r'\$([^$]+)\$', r'<span class="math-expr"><em>\1</em></span>', text)
    
    # Restore code spans
    for i, c in enumerate(code_spans):
        esc_c = html.escape(c)
        text = text.replace(f"__CODE_SPAN_{i}__", f'<code class="inline-code">{esc_c}</code>')
        
    return text

def parse_markdown_block(text):
    lines = text.split('\n')
    out = []
    in_code = False
    code_lang = ""
    code_lines = []
    
    in_ol = False
    in_ul = False
    
    for line in lines:
        stripped = line.strip()
        
        # Code fence
        if stripped.startswith('```'):
            if not in_code:
                if in_ol:
                    out.append('</ol>')
                    in_ol = False
                if in_ul:
                    out.append('</ul>')
                    in_ul = False
                in_code = True
                code_lang = stripped[3:].strip()
                code_lines = []
            else:
                in_code = False
                code_content = html.escape('\n'.join(code_lines))
                out.append(f'<pre class="code-box"><code class="language-{code_lang}">{code_content}</code></pre>')
                code_lines = []
            continue
            
        if in_code:
            code_lines.append(line)
            continue
            
        # Numbered list: 1. or 1)
        m_ol = re.match(r'^\s*(\d+)[.)]\s+(.*)$', line)
        if m_ol:
            if in_ul:
                out.append('</ul>')
                in_ul = False
            if not in_ol:
                out.append('<ol class="step-list">')
                in_ol = True
            out.append(f'<li>{format_inline_md(m_ol.group(2))}</li>')
            continue
            
        # Unordered list: - or *
        m_ul = re.match(r'^\s*[-*]\s+(.*)$', line)
        if m_ul:
            if in_ol:
                out.append('</ol>')
                in_ol = False
            if not in_ul:
                out.append('<ul class="bullet-list">')
                in_ul = True
            out.append(f'<li>{format_inline_md(m_ul.group(1))}</li>')
            continue
            
        # Empty line
        if not stripped:
            if in_ol:
                out.append('</ol>')
                in_ol = False
            if in_ul:
                out.append('</ul>')
                in_ul = False
            continue
            
        # Normal paragraph line
        if in_ol:
            out.append('</ol>')
            in_ol = False
        if in_ul:
            out.append('</ul>')
            in_ul = False
            
        out.append(f'<p>{format_inline_md(line)}</p>')
        
    if in_code:
        code_content = html.escape('\n'.join(code_lines))
        out.append(f'<pre class="code-box"><code>{code_content}</code></pre>')
    if in_ol:
        out.append('</ol>')
    if in_ul:
        out.append('</ul>')
        
    return '\n'.join(out)

def format_locations(loc_str):
    parts = [p.strip() for p in loc_str.split(',') if p.strip()]
    out = []
    for p in parts:
        formatted = format_inline_md(p)
        out.append(f'<span class="loc-tag">📄 {formatted}</span>')
    return ' '.join(out) if out else format_inline_md(loc_str)

def generate_html_report(md_path, html_out_path):
    with open(md_path, 'r', encoding='utf-8') as f:
        md_text = f.read()

    # Split main sections
    # 1. Executive Summary
    exec_match = re.search(r'## 1\. Executive Summary\s*\n(.*?)(?=\n---\s*\n\s*## 2\.|\Z)', md_text, re.DOTALL)
    exec_text = exec_match.group(1).strip() if exec_match else ""

    # 2. Findings
    findings_pattern = r'#### Finding (3\.\d+\.\d+) — (.+?)\n(.*?)(?=\n#### Finding |\n### |\n## |\Z)'
    raw_findings = re.findall(findings_pattern, md_text, re.DOTALL)

    # Subsections in Section 2
    subsections = [
        ("3.1", "§3.1 Cryptography & Protocol", "Primitives, RFC 3526 DH key exchange, nonces, key storage, and wire signatures."),
        ("3.2", "§3.2 Relay / Server Architecture", "Dumb relay queuing, connection registration, socket lifecycle, and network isolation."),
        ("3.3", "§3.3 Client Correctness & State", "Desktop and mobile state machines, ack processing, message deduplication, and parsing."),
        ("3.4", "§3.4 Input Validation & Injection", "Wire codecs, character sets, file permissions, and boundary deserialization."),
        ("3.5", "§3.5 Build, Supply Chain & Secrets", "Dependencies, CVE exposure, release build gates, and SpotBugs static analysis filters."),
        ("3.6", "§3.6 Tests & Verification", "Test vector completeness, adversarial coverage, stress tests, and lifecycle assertions."),
        ("3.7", "§3.7 Architecture, Maintainability & Docs", "Documentation fidelity, specification drift, and code maintainability.")
    ]

    # Map findings by subsection
    findings_by_sub = {sub[0]: [] for sub in subsections}
    severity_counts = {"Critical": 0, "High": 0, "Medium": 0, "Low": 0}

    parsed_findings = []
    for fid, title, body in raw_findings:
        loc = re.search(r'- \*\*Location:\*\* (.*?)(?=\n- \*\*|\Z)', body, re.DOTALL)
        wrong = re.search(r'- \*\*What is wrong:\*\* (.*?)(?=\n- \*\*|\Z)', body, re.DOTALL)
        scen = re.search(r'- \*\*Concrete failure scenario:\*\* (.*?)(?=\n- \*\*|\Z)', body, re.DOTALL)
        matt = re.search(r'- \*\*Why it matters:\*\* (.*?)(?=\n- \*\*|\Z)', body, re.DOTALL)
        fix = re.search(r'- \*\*How to fix:\*\* (.*?)(?=\n- \*\*|\Z)', body, re.DOTALL)
        sev = re.search(r'- \*\*Severity:\*\* (.*?)(?=\n- \*\*|\Z)', body, re.DOTALL)
        conf = re.search(r'- \*\*Confidence:\*\* (.*?)(?=\n- \*\*|\n---|;|\Z)', body, re.DOTALL)

        sev_val = sev.group(1).strip() if sev else "Medium"
        conf_val = conf.group(1).strip() if conf else "Confirmed"
        conf_val = re.sub(r'[\s\-]+$', '', conf_val).strip()

        if sev_val in severity_counts:
            severity_counts[sev_val] += 1

        f_data = {
            "id": fid.strip(),
            "title": title.strip(),
            "location": loc.group(1).strip() if loc else "",
            "wrong": wrong.group(1).strip() if wrong else "",
            "scenario": scen.group(1).strip() if scen else "",
            "matters": matt.group(1).strip() if matt else "",
            "fix": fix.group(1).strip() if fix else "",
            "severity": sev_val,
            "confidence": conf_val
        }
        parsed_findings.append(f_data)

        for sub_prefix in findings_by_sub.keys():
            if fid.startswith(sub_prefix):
                findings_by_sub[sub_prefix].append(f_data)
                break

    # Section 3: Confirmed Strengths
    s3_match = re.search(r'## 3\. Confirmed Strengths\s*\n\s*The following controls were independently verified in the codebase:\s*\n(.*?)(?=\n---\s*\n\s*## 4\.|$)', md_text, re.DOTALL)
    strengths = re.findall(r'(\d+)\.\s+\*\*(.+?)\*\*\s*\n(.*?)(?=\n\d+\.|\Z)', s3_match.group(1), re.DOTALL) if s3_match else []

    # Section 4: Documented Limitations
    s4_match = re.search(r'## 4\. Documented Limitations Confirmed Present\s*\n\s*The following limitations disclosed in .*? were verified in the code:\s*\n(.*?)(?=\n---\s*\n\s*## 5\.|$)', md_text, re.DOTALL)
    limitations = re.findall(r'(\d+)\.\s+\*\*(.+?)\*\*\s*\n(.*?)(?=\n\d+\.|\Z)', s4_match.group(1), re.DOTALL) if s4_match else []

    # Section 5: Top 5 Priorities table
    s5_match = re.search(r'## 5\. Top 5 Priorities to Fix First\s*\n\s*(\|.+)', md_text, re.DOTALL)
    prio_lines = []
    if s5_match:
        raw_lines = [l.strip() for l in s5_match.group(1).strip().split('\n') if l.strip().startswith('|')]
        for line in raw_lines[2:]:
            cols = [c.strip() for c in line.split('|')[1:-1]]
            if len(cols) >= 5:
                prio_lines.append(cols)

    # Build HTML
    html_parts = []
    html_parts.append("""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<title>Tetherless — Security &amp; Engineering Audit Report</title>
<style>
  @page {
    size: A4 portrait;
    margin: 13mm 12mm 13mm 12mm;
  }

  * {
    box-sizing: border-box;
  }

  body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    color: #1e293b;
    background-color: #ffffff;
    line-height: 1.48;
    font-size: 8.9pt;
    margin: 0;
    padding: 0;
  }

  h1, h2, h3, h4, h5 {
    color: #0f172a;
    font-weight: 700;
    margin-top: 0;
    break-after: avoid;
    page-break-after: avoid;
  }

  /* Cover Header Card */
  .cover-card {
    background: linear-gradient(135deg, #090d16 0%, #111827 50%, #1e293b 100%);
    color: #ffffff;
    padding: 20px 22px;
    border-radius: 8px;
    margin-bottom: 14px;
    border: 1px solid #334155;
    page-break-inside: avoid;
  }

  .cover-card .project-badge {
    display: inline-block;
    background: rgba(56, 189, 248, 0.15);
    color: #38bdf8;
    border: 1px solid rgba(56, 189, 248, 0.4);
    padding: 2px 8px;
    border-radius: 4px;
    font-size: 7.5pt;
    font-weight: 700;
    letter-spacing: 0.8px;
    text-transform: uppercase;
    margin-bottom: 6px;
  }

  .cover-card h1 {
    color: #ffffff;
    font-size: 18pt;
    margin: 3px 0 5px 0;
    letter-spacing: -0.4px;
  }

  .cover-card .subtitle {
    color: #94a3b8;
    font-size: 9.8pt;
    font-weight: 400;
    margin-bottom: 14px;
    line-height: 1.4;
  }

  .meta-grid {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 8px;
    background: rgba(15, 23, 42, 0.65);
    padding: 10px 12px;
    border-radius: 6px;
    border: 1px solid rgba(255, 255, 255, 0.08);
  }

  .meta-item {
    font-size: 7.5pt;
    color: #94a3b8;
  }

  .meta-item strong {
    display: block;
    color: #f1f5f9;
    font-size: 8.4pt;
    margin-top: 1px;
  }

  .status-verdict {
    color: #f87171 !important;
  }

  /* Metric KPI Cards */
  .kpi-row {
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    gap: 8px;
    margin-bottom: 14px;
  }

  .kpi-card {
    border-radius: 6px;
    padding: 8px 12px;
    text-align: center;
    border: 1px solid #cbd5e1;
    page-break-inside: avoid;
  }

  .kpi-crit { background-color: #fef2f2; border-color: #fca5a5; }
  .kpi-high { background-color: #fffbeb; border-color: #fde68a; }
  .kpi-med  { background-color: #eff6ff; border-color: #bfdbfe; }
  .kpi-low  { background-color: #f8fafc; border-color: #cbd5e1; }

  .kpi-num {
    font-size: 16pt;
    font-weight: 800;
    line-height: 1;
    margin-bottom: 2px;
  }

  .kpi-crit .kpi-num { color: #dc2626; }
  .kpi-high .kpi-num { color: #d97706; }
  .kpi-med  .kpi-num { color: #2563eb; }
  .kpi-low  .kpi-num { color: #475569; }

  .kpi-label {
    font-size: 7.2pt;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.4px;
  }

  .kpi-crit .kpi-label { color: #991b1b; }
  .kpi-high .kpi-label { color: #92400e; }
  .kpi-med  .kpi-label { color: #1e40af; }
  .kpi-low  .kpi-label { color: #334155; }

  /* Sections */
  .section-container {
    margin-bottom: 16px;
  }

  .section-header {
    border-bottom: 2px solid #0f172a;
    padding-bottom: 4px;
    margin-bottom: 10px;
    margin-top: 14px;
    display: flex;
    justify-content: space-between;
    align-items: baseline;
  }

  .section-header h2 {
    font-size: 12pt;
    color: #0f172a;
    margin: 0;
  }

  .section-header .section-tag {
    font-size: 7.8pt;
    font-weight: 600;
    color: #64748b;
  }

  .subsection-banner {
    background: #f1f5f9;
    border-left: 4px solid #0284c7;
    padding: 6px 10px;
    border-radius: 0 4px 4px 0;
    margin: 12px 0 8px 0;
    page-break-inside: avoid;
    break-after: avoid;
  }

  .subsection-banner h3 {
    font-size: 10pt;
    color: #0f172a;
    margin: 0 0 2px 0;
  }

  .subsection-banner p {
    font-size: 7.6pt;
    color: #475569;
    margin: 0;
  }

  /* Executive Box */
  .exec-box {
    background-color: #f8fafc;
    border: 1px solid #e2e8f0;
    border-left: 4px solid #0f172a;
    border-radius: 4px;
    padding: 10px 12px;
    font-size: 8.6pt;
    line-height: 1.5;
    color: #1e293b;
    margin-bottom: 12px;
    page-break-inside: avoid;
  }

  /* Scope & Methodology Box */
  .scope-box {
    background-color: #f0fdf4;
    border: 1px solid #bbf7d0;
    border-left: 4px solid #16a34a;
    border-radius: 4px;
    padding: 9px 12px;
    font-size: 8.2pt;
    line-height: 1.45;
    color: #166534;
    margin-top: 10px;
    page-break-inside: avoid;
  }

  .scope-box strong {
    color: #14532d;
  }

  /* Finding Card */
  .finding-card {
    border: 1px solid #cbd5e1;
    border-radius: 6px;
    background-color: #ffffff;
    margin-bottom: 12px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.03);
    page-break-inside: avoid;
    break-inside: avoid;
    overflow: hidden;
  }

  .finding-card.sev-Critical { border-top: 3.5px solid #dc2626; }
  .finding-card.sev-High     { border-top: 3.5px solid #d97706; }
  .finding-card.sev-Medium   { border-top: 3.5px solid #2563eb; }
  .finding-card.sev-Low      { border-top: 3.5px solid #64748b; }

  .finding-top {
    background-color: #f8fafc;
    border-bottom: 1px solid #e2e8f0;
    padding: 7px 11px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
  }

  .finding-id-title {
    display: flex;
    align-items: center;
    gap: 7px;
    flex-grow: 1;
  }

  .finding-id-badge {
    background-color: #0f172a;
    color: #ffffff;
    font-weight: 700;
    font-size: 7.5pt;
    padding: 2px 5px;
    border-radius: 3px;
    font-family: Consolas, monospace;
    white-space: nowrap;
  }

  .finding-title {
    font-size: 9.3pt;
    font-weight: 700;
    color: #0f172a;
    margin: 0;
  }

  .finding-badges {
    display: flex;
    align-items: center;
    gap: 5px;
    white-space: nowrap;
  }

  .badge {
    display: inline-block;
    padding: 2px 6px;
    border-radius: 4px;
    font-weight: 700;
    font-size: 7pt;
    text-transform: uppercase;
    letter-spacing: 0.4px;
  }

  .badge-critical { background-color: #fee2e2; color: #b91c1c; border: 1px solid #fca5a5; }
  .badge-high     { background-color: #fef3c7; color: #b45309; border: 1px solid #fde68a; }
  .badge-medium   { background-color: #dbeafe; color: #1d4ed8; border: 1px solid #bfdbfe; }
  .badge-low      { background-color: #f1f5f9; color: #475569; border: 1px solid #cbd5e1; }
  .badge-conf     { background-color: #ecfdf5; color: #047857; border: 1px solid #a7f3d0; }

  .finding-location-bar {
    background-color: #fafafa;
    border-bottom: 1px solid #f1f5f9;
    padding: 5px 11px;
    font-size: 7.4pt;
    color: #475569;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px;
  }

  .loc-tag {
    background: #f1f5f9;
    border: 1px solid #e2e8f0;
    padding: 1px 5px;
    border-radius: 3px;
    font-family: Consolas, monospace;
    font-size: 7.2pt;
    color: #334155;
  }

  .finding-body {
    padding: 8px 11px;
    font-size: 8.4pt;
  }

  .detail-block {
    margin-bottom: 7px;
  }

  .detail-block:last-child {
    margin-bottom: 0;
  }

  .detail-label {
    font-size: 7.4pt;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.3px;
    margin-bottom: 2px;
  }

  .label-wrong    { color: #991b1b; }
  .label-scenario { color: #9a3412; }
  .label-impact   { color: #4338ca; }
  .label-fix      { color: #065f46; }

  .box-wrong {
    background-color: #ffffff;
    border-left: 3px solid #ef4444;
    padding: 5px 9px;
    border-radius: 0 4px 4px 0;
    margin-bottom: 6px;
  }

  .box-scenario {
    background-color: #fff7ed;
    border: 1px solid #fed7aa;
    border-left: 3px solid #ea580c;
    padding: 5px 9px;
    border-radius: 4px;
    margin-bottom: 6px;
  }

  .box-impact {
    background-color: #f5f3ff;
    border: 1px solid #e0e7ff;
    border-left: 3px solid #6366f1;
    padding: 5px 9px;
    border-radius: 4px;
    margin-bottom: 6px;
  }

  .box-fix {
    background-color: #f0fdf4;
    border: 1px solid #bbf7d0;
    border-left: 3px solid #10b981;
    padding: 5px 9px;
    border-radius: 4px;
  }

  /* Code Boxes */
  .code-box {
    background-color: #0f172a;
    color: #f8fafc;
    padding: 6px 9px;
    border-radius: 4px;
    font-family: Consolas, "Fira Code", monospace;
    font-size: 7.4pt;
    line-height: 1.35;
    overflow-x: auto;
    margin: 4px 0;
    border: 1px solid #1e293b;
    page-break-inside: avoid;
  }

  .inline-code {
    font-family: Consolas, "Fira Code", monospace;
    font-size: 7.8pt;
    background-color: #f1f5f9;
    color: #0f172a;
    padding: 1px 3px;
    border-radius: 3px;
    border: 1px solid #e2e8f0;
  }

  .math-expr {
    font-family: "Cambria Math", "Times New Roman", serif;
    font-size: 8.8pt;
    color: #0f172a;
  }

  /* Lists */
  .step-list, .bullet-list {
    margin: 2px 0;
    padding-left: 16px;
  }

  .step-list li, .bullet-list li {
    margin-bottom: 2px;
    line-height: 1.38;
  }

  p {
    margin: 0 0 4px 0;
  }

  p:last-child {
    margin-bottom: 0;
  }

  /* Tables */
  table {
    width: 100%;
    border-collapse: collapse;
    margin-bottom: 12px;
    font-size: 7.8pt;
    page-break-inside: avoid;
  }

  th {
    background-color: #0f172a;
    color: #ffffff;
    text-align: left;
    padding: 5px 7px;
    font-weight: 600;
    border: 1px solid #334155;
    font-size: 7.6pt;
  }

  td {
    padding: 5px 7px;
    border: 1px solid #e2e8f0;
    vertical-align: top;
    line-height: 1.35;
  }

  tr:nth-child(even) td {
    background-color: #f8fafc;
  }

  /* Strengths & Limitations Cards */
  .card-grid {
    display: grid;
    grid-template-columns: 1fr;
    gap: 7px;
  }

  .item-card {
    border: 1px solid #e2e8f0;
    border-radius: 5px;
    padding: 7px 10px;
    background-color: #ffffff;
    page-break-inside: avoid;
  }

  .strength-card {
    border-left: 4px solid #10b981;
    background-color: #fafdfb;
  }

  .limit-card {
    border-left: 4px solid #f59e0b;
    background-color: #fffdfa;
  }

  .item-title {
    font-size: 8.6pt;
    font-weight: 700;
    color: #0f172a;
    margin-bottom: 2px;
    display: flex;
    align-items: center;
    gap: 6px;
  }

  .item-body {
    font-size: 8pt;
    color: #334155;
    line-height: 1.38;
  }

  .icon-badge {
    font-size: 6.8pt;
    font-weight: 800;
    padding: 1px 4px;
    border-radius: 3px;
    text-transform: uppercase;
  }

  .icon-strength { background-color: #d1fae5; color: #065f46; }
  .icon-limit    { background-color: #fef3c7; color: #92400e; }

  /* Priorities Accents */
  .prio-rank {
    font-weight: 800;
    font-size: 8pt;
    text-align: center;
    display: inline-block;
    width: 18px;
    height: 18px;
    line-height: 18px;
    border-radius: 50%;
    background: #0f172a;
    color: white;
  }

  .prio-rank-1 { background: #dc2626; }
  .prio-rank-2 { background: #e11d48; }
  .prio-rank-3 { background: #d97706; }
  .prio-rank-4 { background: #b45309; }
  .prio-rank-5 { background: #2563eb; }

  .page-break {
    page-break-before: always;
    break-before: page;
  }

  .doc-link {
    color: #0284c7;
    text-decoration: none;
  }
</style>
</head>
<body>
""")

    # Page 1: Cover Header Card
    html_parts.append("""
<div class="cover-card">
  <div class="project-badge">Tetherless Secure Communications • Security Audit</div>
  <h1>TETHERLESS: SECURITY &amp; ENGINEERING AUDIT REPORT</h1>
  <div class="subtitle">Comprehensive Independent Architecture, Cryptographic &amp; Source Code Implementation Review</div>
  <div class="meta-grid">
    <div class="meta-item">TARGET SYSTEM<strong>Tetherless (v1.0-SNAPSHOT)</strong></div>
    <div class="meta-item">MODULES IN AUDIT<strong>core-shared, chat-server, chat-desktop</strong></div>
    <div class="meta-item">AUDIT DATE<strong>September 2026</strong></div>
    <div class="meta-item">DEPLOYMENT VERDICT<strong class="status-verdict">NOT READY FOR HOSTILE DEPLOYMENT</strong></div>
  </div>
</div>
""")

    # KPI Row
    html_parts.append(f"""
<div class="kpi-row">
  <div class="kpi-card kpi-crit">
    <div class="kpi-num">{severity_counts['Critical']}</div>
    <div class="kpi-label">Critical Vulnerabilities</div>
  </div>
  <div class="kpi-card kpi-high">
    <div class="kpi-num">{severity_counts['High']}</div>
    <div class="kpi-label">High Severity Findings</div>
  </div>
  <div class="kpi-card kpi-med">
    <div class="kpi-num">{severity_counts['Medium']}</div>
    <div class="kpi-label">Medium Severity Findings</div>
  </div>
  <div class="kpi-card kpi-low">
    <div class="kpi-num">{severity_counts['Low']}</div>
    <div class="kpi-label">Low Severity Findings</div>
  </div>
</div>
""")

    # Section 1: Executive Summary
    html_parts.append("""
<div class="section-container">
  <div class="section-header">
    <h2>1. Executive Summary</h2>
    <span class="section-tag">Systemic Posture &amp; Core Axiom Assessment</span>
  </div>
  <div class="exec-box">
""")
    html_parts.append(parse_markdown_block(exec_text))
    html_parts.append("""
  </div>
</div>
""")

    # Findings Distribution Matrix
    html_parts.append("""
<div class="section-container">
  <div class="section-header">
    <h2>Audit Distribution Matrix</h2>
    <span class="section-tag">Findings by Technical Domain</span>
  </div>
  <table>
    <thead>
      <tr>
        <th style="width: 10%;">Section</th>
        <th>Audit Domain</th>
        <th style="width: 10%; text-align: center;">Critical</th>
        <th style="width: 10%; text-align: center;">High</th>
        <th style="width: 10%; text-align: center;">Medium</th>
        <th style="width: 10%; text-align: center;">Low</th>
        <th style="width: 10%; text-align: center;">Total</th>
      </tr>
    </thead>
    <tbody>
""")
    for prefix, name, desc in subsections:
        f_list = findings_by_sub[prefix]
        c_crit = sum(1 for f in f_list if f['severity'] == 'Critical')
        c_high = sum(1 for f in f_list if f['severity'] == 'High')
        c_med  = sum(1 for f in f_list if f['severity'] == 'Medium')
        c_low  = sum(1 for f in f_list if f['severity'] == 'Low')
        c_tot  = len(f_list)
        html_parts.append(f"""
      <tr>
        <td><strong>{prefix}</strong></td>
        <td><strong>{name}</strong><br><span style="color: #64748b; font-size: 7.2pt;">{desc}</span></td>
        <td style="text-align: center; font-weight: 700; color: {'#dc2626' if c_crit else '#94a3b8'};">{c_crit}</td>
        <td style="text-align: center; font-weight: 700; color: {'#d97706' if c_high else '#94a3b8'};">{c_high}</td>
        <td style="text-align: center; font-weight: 700; color: {'#2563eb' if c_med else '#94a3b8'};">{c_med}</td>
        <td style="text-align: center; font-weight: 700; color: {'#475569' if c_low else '#94a3b8'};">{c_low}</td>
        <td style="text-align: center; font-weight: 800; background: #f1f5f9;">{c_tot}</td>
      </tr>
""")
    html_parts.append(f"""
      <tr style="background: #e2e8f0; font-weight: 800;">
        <td colspan="2">Total Audit Findings Across All Domains</td>
        <td style="text-align: center; color: #dc2626;">{severity_counts['Critical']}</td>
        <td style="text-align: center; color: #d97706;">{severity_counts['High']}</td>
        <td style="text-align: center; color: #2563eb;">{severity_counts['Medium']}</td>
        <td style="text-align: center; color: #475569;">{severity_counts['Low']}</td>
        <td style="text-align: center; font-size: 8.8pt;">23</td>
      </tr>
    </tbody>
  </table>

  <div class="scope-box">
    <strong>Audit Scope &amp; Verification Methodology:</strong> Complete architectural and source code security review of the Tetherless multi-module codebase (<code class="inline-code">core-shared</code>, <code class="inline-code">chat-server</code>, <code class="inline-code">chat-desktop</code>). Verification evaluated cryptographic correctness (AES-256-GCM nonces, RFC 3526 DH group validation, HKDF derivation), wire framing integrity, state-machine monotonicity, relay isolation against actively hostile operators, supply chain dependencies, and build gates. Automated build and regression verification passed cleanly across all modules (<code class="inline-code">:core-shared:check</code>, <code class="inline-code">:chat-server:check</code>, <code class="inline-code">:chat-desktop:check</code>, <code class="inline-code">:chat-desktop:integTest</code>).
  </div>
</div>
""")

    # Page Break to Page 2: Detailed Findings
    html_parts.append("""
<div class="page-break"></div>
<div class="section-container">
  <div class="section-header">
    <h2>2. Findings Report</h2>
    <span class="section-tag">Prioritized Defect Specifications (§3.1 – §3.7)</span>
  </div>
""")

    for prefix, name, desc in subsections:
        f_list = findings_by_sub[prefix]
        if not f_list:
            continue

        html_parts.append(f"""
  <div class="subsection-banner">
    <h3>{name}</h3>
    <p>{desc} — {len(f_list)} findings identified in this domain.</p>
  </div>
""")

        for f in f_list:
            sev_class = f"sev-{f['severity']}"
            badge_class = f"badge-{f['severity'].lower()}"
            html_parts.append(f"""
  <div class="finding-card {sev_class}">
    <div class="finding-top">
      <div class="finding-id-title">
        <span class="finding-id-badge">Finding {f['id']}</span>
        <h4 class="finding-title">{format_inline_md(f['title'])}</h4>
      </div>
      <div class="finding-badges">
        <span class="badge {badge_class}">{f['severity']}</span>
        <span class="badge badge-conf">{f['confidence']}</span>
      </div>
    </div>
    <div class="finding-location-bar">
      <strong>Location:</strong> {format_locations(f['location'])}
    </div>
    <div class="finding-body">
      <div class="detail-block">
        <div class="detail-label label-wrong">What is Wrong (Defect Mechanics)</div>
        <div class="box-wrong">
          {parse_markdown_block(f['wrong'])}
        </div>
      </div>
      <div class="detail-block">
        <div class="detail-label label-scenario">Concrete Failure Scenario</div>
        <div class="box-scenario">
          {parse_markdown_block(f['scenario'])}
        </div>
      </div>
      <div class="detail-block">
        <div class="detail-label label-impact">Why it Matters (Cryptographic &amp; Systemic Impact)</div>
        <div class="box-impact">
          {parse_markdown_block(f['matters'])}
        </div>
      </div>
      <div class="detail-block">
        <div class="detail-label label-fix">Remediation Guidance (How to Fix)</div>
        <div class="box-fix">
          {parse_markdown_block(f['fix'])}
        </div>
      </div>
    </div>
  </div>
""")

    html_parts.append("""
</div>
""")

    # Section 3: Confirmed Strengths
    html_parts.append("""
<div class="page-break"></div>
<div class="section-container">
  <div class="section-header">
    <h2>3. Confirmed Strengths</h2>
    <span class="section-tag">7 Independently Verified Security Controls</span>
  </div>
  <p style="margin-bottom: 10px; color: #475569; font-size: 8.2pt;">The following architectural mechanisms and cryptographic controls were verified in the source code as effective implementations conforming to secure design principles:</p>
  <div class="card-grid">
""")
    for num, title, desc in strengths:
        html_parts.append(f"""
    <div class="item-card strength-card">
      <div class="item-title">
        <span class="icon-badge icon-strength">VERIFIED #{num}</span>
        {format_inline_md(title)}
      </div>
      <div class="item-body">
        {parse_markdown_block(desc)}
      </div>
    </div>
""")
    html_parts.append("""
  </div>
</div>
""")

    # Section 4: Documented Limitations Confirmed Present
    html_parts.append("""
<div class="section-container" style="margin-top: 16px;">
  <div class="section-header">
    <h2>4. Documented Limitations Confirmed Present</h2>
    <span class="section-tag">6 Verified Boundary Non-Goals</span>
  </div>
  <p style="margin-bottom: 10px; color: #475569; font-size: 8.2pt;">The following documented limitations from <code class="inline-code">docs/security.md §5</code> were independently verified as present in the implementation:</p>
  <div class="card-grid">
""")
    for num, title, desc in limitations:
        html_parts.append(f"""
    <div class="item-card limit-card">
      <div class="item-title">
        <span class="icon-badge icon-limit">LIMITATION #{num}</span>
        {format_inline_md(title)}
      </div>
      <div class="item-body">
        {parse_markdown_block(desc)}
      </div>
    </div>
""")
    html_parts.append("""
  </div>
</div>
""")

    # Section 5: Top 5 Priorities to Fix First
    html_parts.append("""
<div class="page-break"></div>
<div class="section-container">
  <div class="section-header">
    <h2>5. Top 5 Priorities to Fix First</h2>
    <span class="section-tag">Immediate Engineering Action Plan</span>
  </div>
  <p style="margin-bottom: 10px; color: #475569; font-size: 8.2pt;">To achieve baseline cryptographic integrity and prevent active relay compromise, the following 5 remediation items must be implemented prior to release:</p>
  <table>
    <thead>
      <tr>
        <th style="width: 7%; text-align: center;">Rank</th>
        <th style="width: 14%;">Finding ID</th>
        <th style="width: 11%; text-align: center;">Severity</th>
        <th style="width: 28%;">File Reference</th>
        <th>Action Required</th>
      </tr>
    </thead>
    <tbody>
""")
    for cols in prio_lines:
        rank = cols[0].replace('*', '').strip()
        fid = cols[1].replace('*', '').strip()
        sev = cols[2].replace('*', '').strip()
        fileref = cols[3].strip()
        action = cols[4].strip()

        sev_badge = f"badge-{sev.lower()}"
        rank_badge = f"prio-rank prio-rank-{rank}"

        html_parts.append(f"""
      <tr>
        <td style="text-align: center;"><span class="{rank_badge}">{rank}</span></td>
        <td><strong>Finding {fid}</strong></td>
        <td style="text-align: center;"><span class="badge {sev_badge}">{sev}</span></td>
        <td>{format_inline_md(fileref)}</td>
        <td><strong>{format_inline_md(action)}</strong></td>
      </tr>
""")
    html_parts.append("""
    </tbody>
  </table>
</div>

</body>
</html>
""")

    full_html = "".join(html_parts)
    with open(html_out_path, 'w', encoding='utf-8') as f:
        f.write(full_html)
    print(f"Generated HTML report: {html_out_path} ({len(full_html):,} bytes)")

def convert_to_pdf(html_path, pdf_path):
    edge_paths = [
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    ]
    edge_bin = None
    for p in edge_paths:
        if os.path.exists(p):
            edge_bin = p
            break

    if not edge_bin:
        print("Error: Microsoft Edge not found.")
        sys.exit(1)

    print(f"Converting HTML to PDF via headless Edge: {edge_bin}...")
    args = [
        edge_bin,
        "--headless",
        "--disable-gpu",
        "--no-pdf-header-footer",
        f"--print-to-pdf={pdf_path}",
        html_path,
    ]
    res = subprocess.run(args, capture_output=True, text=True)
    if os.path.exists(pdf_path) and os.path.getsize(pdf_path) > 0:
        print(f"Successfully generated PDF: {pdf_path} ({os.path.getsize(pdf_path):,} bytes)")
    else:
        print(f"Failed to generate PDF. Exit code: {res.returncode}")
        print("Stdout:", res.stdout)
        print("Stderr:", res.stderr)
        sys.exit(1)

if __name__ == "__main__":
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "docs"))
    md_file = os.path.join(base_dir, "tetherless_security_audit_report.md")
    html_file = os.path.join(base_dir, "tetherless_security_audit_report.html")
    pdf_file = os.path.join(base_dir, "tetherless_security_audit_report.pdf")
    generate_html_report(md_file, html_file)
    convert_to_pdf(html_file, pdf_file)
