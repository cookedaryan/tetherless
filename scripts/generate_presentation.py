import os
import sys
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE

# --- PURE MONOCHROMATIC PALETTE (Minimalist High-Contrast Technical) ---
BG_BLACK = RGBColor(0x0A, 0x0A, 0x0C)       # #0A0A0C Pure Deep Black
NAV_BG = RGBColor(0x12, 0x12, 0x15)         # #121215 Nav Bar Charcoal
CARD_BG = RGBColor(0x14, 0x14, 0x18)        # #141418 Dark Card Slate
CARD_BG_ACTIVE = RGBColor(0x22, 0x22, 0x28) # #222228 Active Nav/Badge
CARD_BORDER = RGBColor(0x2A, 0x2A, 0x32)    # #2A2A32 Subtle Border
BORDER_LIGHT = RGBColor(0x52, 0x52, 0x5B)   # #52525B Elevated Border
WHITE = RGBColor(0xFF, 0xFF, 0xFF)          # #FFFFFF Pure Crisp White
GRAY_LIGHT = RGBColor(0xE4, 0xE4, 0xE7)     # #E4E4E7 Zinc 200 Primary Text
GRAY_MID = RGBColor(0xA1, 0xA1, 0xAA)       # #A1A1AA Zinc 400 Secondary Text
GRAY_MUTED = RGBColor(0x71, 0x71, 0x7A)     # #71717A Zinc 500 Dim Text
LINK_COLOR = RGBColor(0xFF, 0xFF, 0xFF)     # #FFFFFF Clickable Link Highlight

FONT_HEADING = "Segoe UI"
FONT_BODY = "Segoe UI"
FONT_CODE = "Consolas"

def init_presentation():
    prs = Presentation()
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)
    return prs

def create_slide(prs):
    slide = prs.slides.add_slide(prs.slide_layouts[6])
    bg = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, prs.slide_width, prs.slide_height)
    bg.fill.solid()
    bg.fill.fore_color.rgb = BG_BLACK
    bg.line.fill.background()
    return slide

def add_navbar(slide, current_index, all_slides):
    """
    Adds a clean, monochromatic interactive navigation bar with section anchors,
    prev/next navigation buttons, and slide counter.
    """
    nav_h = Inches(0.44)
    nav_bar = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, 0, 0, Inches(13.333), nav_h)
    nav_bar.fill.solid()
    nav_bar.fill.fore_color.rgb = NAV_BG
    nav_bar.line.fill.background()

    # Brand Title
    tb_brand = slide.shapes.add_textbox(Inches(0.6), Inches(0.07), Inches(2.3), Inches(0.3))
    tf_b = tb_brand.text_frame
    tf_b.margin_left = tf_b.margin_top = tf_b.margin_right = tf_b.margin_bottom = 0
    p_b = tf_b.paragraphs[0]
    p_b.text = "TETHERLESS E2EE"
    p_b.font.name = FONT_HEADING
    p_b.font.size = Pt(10)
    p_b.font.bold = True
    p_b.font.color.rgb = WHITE

    # 5 Major Technical Sections (3 slides each)
    sections = [
        ("Architecture", 0, [0, 1, 2]),
        ("Cryptography", 3, [3, 4, 5]),
        ("Threat Model", 6, [6, 7, 8]),
        ("Relay System", 9, [9, 10, 11]),
        ("Clients & MVP", 12, [12, 13, 14])
    ]

    btn_w = Inches(1.5)
    start_x = Inches(3.0)
    gap = Inches(0.08)

    for label, target_idx, active_indices in sections:
        x = start_x + (sections.index((label, target_idx, active_indices))) * (btn_w + gap)
        btn = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, x, Inches(0.07), btn_w, Inches(0.30))
        btn.fill.solid()
        
        is_active = (current_index in active_indices)
        btn.fill.fore_color.rgb = CARD_BG_ACTIVE if is_active else NAV_BG
        btn.line.color.rgb = WHITE if is_active else CARD_BORDER
        btn.line.width = Pt(1.0 if is_active else 0.5)

        tf = btn.text_frame
        tf.margin_left = tf.margin_top = tf.margin_right = tf.margin_bottom = 0
        p = tf.paragraphs[0]
        p.alignment = PP_ALIGN.CENTER
        p.text = label
        p.font.name = FONT_HEADING
        p.font.size = Pt(9)
        p.font.bold = is_active
        p.font.color.rgb = WHITE if is_active else GRAY_MID

        btn.click_action.target_slide = all_slides[target_idx]

    # Prev / Next Controls
    btn_prev = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(11.1), Inches(0.07), Inches(0.38), Inches(0.30))
    btn_prev.fill.solid()
    btn_prev.fill.fore_color.rgb = CARD_BG_ACTIVE
    btn_prev.line.color.rgb = CARD_BORDER
    btn_prev.line.width = Pt(0.5)
    tf_pr = btn_prev.text_frame
    tf_pr.margin_left = tf_pr.margin_top = tf_pr.margin_right = tf_pr.margin_bottom = 0
    p_pr = tf_pr.paragraphs[0]
    p_pr.alignment = PP_ALIGN.CENTER
    p_pr.text = "←"
    p_pr.font.name = FONT_HEADING
    p_pr.font.size = Pt(10)
    p_pr.font.color.rgb = WHITE if current_index > 0 else GRAY_MUTED
    if current_index > 0:
        btn_prev.click_action.target_slide = all_slides[current_index - 1]

    btn_next = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(11.55), Inches(0.07), Inches(0.38), Inches(0.30))
    btn_next.fill.solid()
    btn_next.fill.fore_color.rgb = CARD_BG_ACTIVE
    btn_next.line.color.rgb = CARD_BORDER
    btn_next.line.width = Pt(0.5)
    tf_nx = btn_next.text_frame
    tf_nx.margin_left = tf_nx.margin_top = tf_nx.margin_right = tf_nx.margin_bottom = 0
    p_nx = tf_nx.paragraphs[0]
    p_nx.alignment = PP_ALIGN.CENTER
    p_nx.text = "→"
    p_nx.font.name = FONT_HEADING
    p_nx.font.size = Pt(10)
    p_nx.font.color.rgb = WHITE if current_index < len(all_slides) - 1 else GRAY_MUTED
    if current_index < len(all_slides) - 1:
        btn_next.click_action.target_slide = all_slides[current_index + 1]

    # Slide Counter
    tb_count = slide.shapes.add_textbox(Inches(12.05), Inches(0.07), Inches(1.1), Inches(0.3))
    tf_c = tb_count.text_frame
    tf_c.margin_left = tf_c.margin_top = tf_c.margin_right = tf_c.margin_bottom = 0
    p_c = tf_c.paragraphs[0]
    p_c.alignment = PP_ALIGN.RIGHT
    p_c.text = f"{current_index + 1:02d} / {len(all_slides):02d}"
    p_c.font.name = FONT_CODE
    p_c.font.size = Pt(10)
    p_c.font.bold = True
    p_c.font.color.rgb = GRAY_LIGHT

def add_header(slide, category_tag, title, subtitle=None):
    # Technical Category Badge
    badge_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.58), Inches(6.0), Inches(0.26))
    tf_b = badge_box.text_frame
    tf_b.word_wrap = True
    tf_b.margin_left = tf_b.margin_top = tf_b.margin_right = tf_b.margin_bottom = 0
    p_b = tf_b.paragraphs[0]
    p_b.text = category_tag.upper()
    p_b.font.name = FONT_HEADING
    p_b.font.size = Pt(9)
    p_b.font.bold = True
    p_b.font.color.rgb = GRAY_MID

    # Main Title
    title_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.86), Inches(11.733), Inches(0.55))
    tf_t = title_box.text_frame
    tf_t.word_wrap = True
    tf_t.margin_left = tf_t.margin_top = tf_t.margin_right = tf_t.margin_bottom = 0
    p_t = tf_t.paragraphs[0]
    p_t.text = title
    p_t.font.name = FONT_HEADING
    p_t.font.size = Pt(21)
    p_t.font.bold = True
    p_t.font.color.rgb = WHITE

    # Subtitle
    if subtitle:
        sub_box = slide.shapes.add_textbox(Inches(0.8), Inches(1.42), Inches(11.733), Inches(0.35))
        tf_s = sub_box.text_frame
        tf_s.word_wrap = True
        tf_s.margin_left = tf_s.margin_top = tf_s.margin_right = tf_s.margin_bottom = 0
        p_s = tf_s.paragraphs[0]
        p_s.text = subtitle
        p_s.font.name = FONT_BODY
        p_s.font.size = Pt(12)
        p_s.font.color.rgb = GRAY_MID

def add_card(slide, left, top, width, height, title=None, bg_color=CARD_BG, border_color=CARD_BORDER):
    shape = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, left, top, width, height)
    shape.fill.solid()
    shape.fill.fore_color.rgb = bg_color
    if border_color:
        shape.line.color.rgb = border_color
        shape.line.width = Pt(1.0)
    else:
        shape.line.fill.background()

    tf = shape.text_frame
    tf.word_wrap = True
    tf.margin_left = Inches(0.24)
    tf.margin_right = Inches(0.24)
    tf.margin_top = Inches(0.20)
    tf.margin_bottom = Inches(0.20)
    tf.vertical_anchor = MSO_ANCHOR.TOP

    if title:
        p = tf.paragraphs[0]
        p.text = title
        p.font.name = FONT_HEADING
        p.font.size = Pt(13)
        p.font.bold = True
        p.font.color.rgb = WHITE
        p.space_after = Pt(6)
    return tf

def add_bullet_item(tf, bold_prefix, text, pt_size=11, text_color=GRAY_LIGHT, is_code=False, space_after=5, ref_tag=None, ref_url=None):
    p = tf.add_paragraph()
    p.space_after = Pt(space_after)
    p.font.size = Pt(pt_size)
    p.font.name = FONT_CODE if is_code else FONT_BODY

    if bold_prefix:
        r1 = p.add_run()
        r1.text = bold_prefix + ": " if not bold_prefix.endswith(":") else bold_prefix + " "
        r1.font.bold = True
        r1.font.color.rgb = WHITE
        r1.font.size = Pt(pt_size)

    r2 = p.add_run()
    r2.text = text
    r2.font.bold = False
    r2.font.color.rgb = text_color
    r2.font.size = Pt(pt_size)

    if ref_tag:
        r3 = p.add_run()
        r3.text = f" [{ref_tag}]"
        r3.font.bold = True
        r3.font.size = Pt(pt_size - 1)
        r3.font.color.rgb = LINK_COLOR
        if ref_url:
            r3.hyperlink.address = ref_url

def build_monochrome_presentation(output_path):
    prs = init_presentation()

    # Pre-create all 15 blank slides so cross-references in navbar work cleanly
    all_slides = [create_slide(prs) for _ in range(15)]

    # =========================================================================
    # SLIDE 1: TITLE & EXECUTIVE BLUEPRINT
    # =========================================================================
    s1 = all_slides[0]
    add_navbar(s1, 0, all_slides)
    add_header(s1, "System Blueprint", "TETHERLESS: Decentralized E2EE Messaging", "Executive Overview & System Architecture")

    tf_l1 = add_card(s1, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="The Zero-Trust Relay Axiom")
    add_bullet_item(tf_l1, "Active Adversary Model", "The relay server is assumed compromised, malicious, or state-monitored. It will inspect, alter, drop, or replay every byte it can.")
    add_bullet_item(tf_l1, "Zero Plaintext Knowledge", "The relay holds zero cryptographic keys and never sees plaintext. Message payload bytes are strictly opaque ciphertext.")
    add_bullet_item(tf_l1, "Reply Metadata Hidden", "Quotes and conversation context travel inside the ciphertext envelope, hiding communication sub-graphs from intermediate nodes.")
    add_bullet_item(tf_l1, "Dual-Layer Protection", "TLS 1.3 is applied in addition to E2EE for transport integrity and traffic hiding, never as a substitute for E2EE.")
    add_bullet_item(tf_l1, "Theoretical Foundation", "Adheres to the formal secure messaging taxonomy of Unger et al.", text_color=GRAY_MID, ref_tag="Unger et al., IEEE S&P 2015", ref_url="https://ieeexplore.ieee.org/document/7163042")

    tf_r1 = add_card(s1, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="Core Architectural Invariants")
    add_bullet_item(tf_r1, "End-to-End Privacy", "Messages encrypted on sender device and decrypted only on receiver device. Mathematical confidentiality guaranteed by AES-256-GCM.")
    add_bullet_item(tf_r1, "Cryptographic Identities", "Addresses derived from identity public key hashes, preventing display name spoofing or account displacement.")
    add_bullet_item(tf_r1, "Tamper-Evidence", "All routed frames cryptographically signed with RSA-2048 identity keys over deterministic byte encodings.")
    add_bullet_item(tf_r1, "Memory Isolation", "Session keys derived via HKDF-SHA256 exist in transient memory only; keys are zeroed and discarded on restart.")
    add_bullet_item(tf_r1, "OTR Lineage", "Follows early Off-the-Record paradigms establishing ephemeral keys over untrusted networks.", text_color=GRAY_MID, ref_tag="Borisov et al., WPES 2004", ref_url="https://doi.org/10.1145/1029179.1029200")

    # =========================================================================
    # SLIDE 2: MODULE TOPOLOGY & TRUST BOUNDARIES
    # =========================================================================
    s2 = all_slides[1]
    add_navbar(s2, 1, all_slides)
    add_header(s2, "Module Topology", "Multi-Module Codebase Architecture", "Four decoupled Gradle modules separated by rigid platform and trust boundaries")

    mods = [
        ("core-shared (Platform-Neutral Core)", "Cryptographic primitives (AES/DH/RSA), wire codec, session state machine, HKDF. Emits Java 8 bytecode for Android compatibility and Java 11+ for desktop."),
        ("chat-server (Untrusted Dumb Relay)", "High-throughput ciphertext forwarder. TLS 1.3 server, non-blocking queue routing, token-bucket rate limiting, loopback Prometheus metrics. Holds zero keys."),
        ("chat-desktop (Java Swing Client)", "Swing desktop UI, background worker thread pool, SQLite in WAL mode, PBKDF2 column encryption, and jpackage Windows .msi bundling."),
        ("chat-mobile (Android Client)", "Modern Jetpack stack: Room ORM, SQLCipher whole-file encryption, AndroidKeyStore hardware keys, and persistent foreground socket service.")
    ]
    for idx, (m_title, m_desc) in enumerate(mods):
        y_pos = Inches(1.85) + idx * Inches(1.28)
        tf_m = add_card(s2, Inches(0.8), y_pos, Inches(11.733), Inches(1.15), title=m_title)
        p = tf_m.add_paragraph()
        p.text = m_desc
        p.font.name = FONT_BODY
        p.font.size = Pt(11)
        p.font.color.rgb = GRAY_LIGHT

    # =========================================================================
    # SLIDE 3: BINARY WIRE CODEC VS. SERIALIZATION
    # =========================================================================
    s3 = all_slides[2]
    add_navbar(s3, 2, all_slides)
    add_header(s3, "Wire Codec & Serialization", "Binary Wire Framing vs. Java Serialization", "Eliminating remote code execution (RCE) and memory leaks at the network boundary")

    tf_l3 = add_card(s3, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="The Java Deserialization Hazard (Legacy)")
    add_bullet_item(tf_l3, "RCE Vulnerability", "ObjectInputStream.readObject() executes arbitrary gadget chains during instantiation, exposing clients and relay to RCE.", ref_tag="Frohoff & Lawrence, AppSecCali 2015", ref_url="https://frohoff.github.io/appseccali-marshalling-pickles/")
    add_bullet_item(tf_l3, "Vulnerability Taxonomy", "Classified as CWE-502 (Deserialization of Untrusted Data).", ref_tag="CWE-502", ref_url="https://cwe.mitre.org/data/definitions/502.html")
    add_bullet_item(tf_l3, "Memory Bloat Risk", "ObjectOutputStream maintains internal object back-reference tables, causing memory leaks and eventual OutOfMemoryError crashes.")
    add_bullet_item(tf_l3, "Allocation Bomb", "Malformed frame length headers could trigger multi-gigabyte array allocations, causing immediate remote Denial of Service.")

    tf_r3 = add_card(s3, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="The Tetherless Binary Frame Codec")
    add_bullet_item(tf_r3, "Framing Format", "[4-byte length] [1-byte version] [wireCode: int] [msgId: UTF] [sender: UTF] [receiver: UTF] [ts: long] [iv] [payload] [signature]", is_code=True)
    add_bullet_item(tf_r3, "Pre-Allocation Caps", "IDs <= 128B, Payload <= 64KiB, Signature <= 512B, Frame <= 1MiB. Rejects oversized frames BEFORE memory allocation.")
    add_bullet_item(tf_r3, "Zero Native Serialization", "Serializable interface completely purged from repository. Codec uses explicit DataInput/DataOutput parsing with typed ProtocolException.")
    add_bullet_item(tf_r3, "Fuzz-Tested Resilience", "Subjected to 20,000 random bit mutations and fuzz tests; guarantees clean typed rejection without crashing.")

    # =========================================================================
    # SLIDE 4: CRYPTOGRAPHIC SUITE & PRIMITIVES
    # =========================================================================
    s4 = all_slides[3]
    add_navbar(s4, 3, all_slides)
    add_header(s4, "Cryptographic Specification", "Cryptographic Primitives & Specifications", "Adherence to vetted standards, constant-time algorithms, and safe parameters")

    tf_l4 = add_card(s4, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="Cipher Suite & Key Agreement")
    add_bullet_item(tf_l4, "Payload Encryption", "AES-256-GCM with 96-bit nonce and 128-bit authentication tag. Provides confidentiality and authenticated integrity.", ref_tag="NIST SP 800-38D", ref_url="https://csrc.nist.gov/publications/detail/sp/800-38d/final")
    add_bullet_item(tf_l4, "Diffie-Hellman Group", "RFC 3526 MODP Group 14 (2048-bit safe prime, g=2). Replaces expensive safe-prime generation (<50ms vs ~30s).", ref_tag="RFC 3526", ref_url="https://datatracker.ietf.org/doc/html/rfc3526")
    add_bullet_item(tf_l4, "Group Validation", "Inbound DH public keys validated: rejected if y <= 1, y >= p-1, or y^q mod p != 1. Completely blocks small-subgroup attacks.")
    add_bullet_item(tf_l4, "Foundational Theory", "Diffie-Hellman public key cryptography foundation.", text_color=GRAY_MID, ref_tag="Diffie & Hellman, IEEE TIT 1976", ref_url="https://ieeexplore.ieee.org/document/1055638")

    tf_r4 = add_card(s4, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="Signatures & Transport Protection")
    add_bullet_item(tf_r4, "Identity & Signatures", "RSA-2048 with SHA256withRSA. Signs handshakes and messages over canonical deterministically ordered byte representations.")
    add_bullet_item(tf_r4, "Anti-Stripping Guard", "The codec treats missing signatures on signed types as fatal protocol errors, preventing signature-stripping downgrade attacks.")
    add_bullet_item(tf_r4, "At-Rest Password KDF", "PBKDF2-HMAC-SHA256 at 210,000 iterations over random 16-byte salt for desktop database encryption key derivation.", ref_tag="NIST SP 800-132", ref_url="https://csrc.nist.gov/publications/detail/sp/800-132/final")
    add_bullet_item(tf_r4, "Transport Security", "TLS 1.3 restricted to AEAD cipher suites with strict certificate pinning on both desktop and mobile clients.", ref_tag="RFC 8446", ref_url="https://datatracker.ietf.org/doc/html/rfc8446")

    # =========================================================================
    # SLIDE 5: DETERMINISTIC NONCES & GCM SAFETY
    # =========================================================================
    s5 = all_slides[4]
    add_navbar(s5, 4, all_slides)
    add_header(s5, "Nonce Construction & GCM Safety", "Deterministic GCM Nonce Construction", "Eliminating catastrophic AES-GCM nonce reuse across communication directions")

    tf_l5 = add_card(s5, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="The AES-GCM Nonce Reuse Hazard")
    add_bullet_item(tf_l5, "Catastrophic Failure", "Reusing an IV/nonce under the same key in GCM immediately leaks the XOR of plaintexts and allows polynomial forgery of authentication tags.", ref_tag="McGrew & Viega, INDOCRYPT 2004", ref_url="https://doi.org/10.1007/978-3-540-30576-7_27")
    add_bullet_item(tf_l5, "Random IV Flaw", "A random 96-bit IV has a non-negligible collision probability by the Birthday Paradox over millions of messages.")
    add_bullet_item(tf_l5, "Bidirectional Collision", "Early bug discovery: Alice and Bob both starting at counter 0 with same key collided immediately on message #1 across directions.")

    tf_r5 = add_card(s5, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="Tetherless Deterministic Nonce")
    add_bullet_item(tf_r5, "Structure", "12-byte (96-bit) buffer: [Direction: 4 bytes] || [Monotonic Counter: 8 bytes]", is_code=True)
    add_bullet_item(tf_r5, "Direction Derivation", "Direction bit is deterministically computed by lexicographical sorting of Peer IDs: Initiator = 0x00000000, Responder = 0x00000001.")
    add_bullet_item(tf_r5, "Send Budget Cap", "Hard cap of 100,000 messages per session key. Attempting to exceed budget triggers mandatory rekeying, preventing counter wraparound.")
    add_bullet_item(tf_r5, "Empirical Verification", "Verified across 100,000 continuous message transmissions with zero duplicate nonces (IvReuseTest).")

    # =========================================================================
    # SLIDE 6: KEY DERIVATION & NORMALIZATION
    # =========================================================================
    s6 = all_slides[5]
    add_navbar(s6, 5, all_slides)
    add_header(s6, "Key Derivation & Normalization", "HKDF-SHA256 Derivation & Normalization", "Extract-then-Expand PRF and solving cross-platform leading zero byte bugs")

    tf_l6 = add_card(s6, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="HKDF-SHA256 Key Derivation")
    add_bullet_item(tf_l6, "Extract Phase", "PRK = HMAC-SHA256(salt = nonceA || nonceB, IKM = DH_shared_secret). Extracts pseudorandom key from Diffie-Hellman secret.")
    add_bullet_item(tf_l6, "Expand Phase", "OKM = HMAC-SHA256(PRK, info = 'tetherless-v1 aes-256-gcm' || 0x01, L = 32 bytes). Derives cryptographically isolated session key.")
    add_bullet_item(tf_l6, "Formal Specification", "Formal security analysis of Extract-then-Expand PRF paradigms.", text_color=GRAY_MID, ref_tag="Krawczyk, CRYPTO 2010 / RFC 5869", ref_url="https://datatracker.ietf.org/doc/html/rfc5869")
    add_bullet_item(tf_l6, "Test Vector Verified", "Verified against published RFC 5869 golden test vectors in CryptoVectorsTest.")

    tf_r6 = add_card(s6, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="Leading Zero Padding Normalization")
    add_bullet_item(tf_r6, "The Intermittent Flaw", "DH shared secrets can produce leading zero bytes (~1 in 256 handshakes). Conscrypt (Android) and SunJCE (desktop) disagree on stripping them.")
    add_bullet_item(tf_r6, "Catastrophic Consequence", "Without normalization, JVM and Android derived different keys on ~0.4% of sessions, causing silent handshake failures.")
    add_bullet_item(tf_r6, "The Deterministic Fix", "Left-pad the shared secret to the exact byte length of modulus p before feeding HKDF.")
    add_bullet_item(tf_r6, "Empirical Proof", "1,000 consecutive handshakes derive matching keys with zero mismatches (CryptoVectorsTest).")

    # =========================================================================
    # SLIDE 7: FORMAL THREAT MATRIX (T1 - T6)
    # =========================================================================
    s7 = all_slides[6]
    add_navbar(s7, 6, all_slides)
    add_header(s7, "Formal Threat Matrix", "Threat Matrix: Attacks T1 through T6", "Comprehensive mitigations and automated verification backing each security claim")

    threats = [
        ("T1: Passive Relay Sniffing", "AES-256-GCM encryption with client-held keys. Verified by EndToEndExchangeTest with live wire tap asserting zero plaintext."),
        ("T2: Active Relay MITM", "Handshake signed with long-term RSA key; Peer ID bound to hash of public key. Verified by AdversarialRelayTest MITM scenarios."),
        ("T3: Network Interception", "Pinned TLS 1.3 transport. Protects metadata and connection integrity from intermediate ISPs and eavesdroppers."),
        ("T4: Replay & Reordering", "Monotonic counter sliding window (floor = highest - 1024) + 5-min timestamp skew check. Replayed frames rejected silently."),
        ("T5: Malicious Peer Payload", "Explicit length-prefixed binary codec with per-field size limits. Fuzz-tested with 20,000 mutations; no deserialization sinks."),
        ("T6: Device Compromise at Rest", "SQLCipher full-database encryption on Android (AndroidKeyStore); PBKDF2 column encryption on Desktop. Keys never written in plaintext.")
    ]
    for idx, (t_title, t_desc) in enumerate(threats):
        row = idx // 2
        col_idx = idx % 2
        x = Inches(0.8) if col_idx == 0 else Inches(6.8)
        y = Inches(1.85) + row * Inches(1.68)
        tf_t = add_card(s7, x, y, Inches(5.7), Inches(1.5), title=t_title)
        p = tf_t.add_paragraph()
        p.text = t_desc
        p.font.name = FONT_BODY
        p.font.size = Pt(11)
        p.font.color.rgb = GRAY_LIGHT

    # =========================================================================
    # SLIDE 8: IDENTITY & TRUST ARCHITECTURE
    # =========================================================================
    s8 = all_slides[7]
    add_navbar(s8, 7, all_slides)
    add_header(s8, "Identity & Trust Architecture", "Identity Architecture: TOFU & Safety Numbers", "Binding identities cryptographically rather than relying on mutable display names")

    tf_l8 = add_card(s8, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="Cryptographic Peer Identity")
    add_bullet_item(tf_l8, "Address Derivation", "Peer ID = SHA-256(PublicKey)[0..16] (32 lowercase hex characters). Address never changes when a user changes display name.")
    add_bullet_item(tf_l8, "Self-Signed HELLO Proof", "Clients transmit public key and sign their HELLO frame. Relay and peers reject any client whose ID does not match key hash.")
    add_bullet_item(tf_l8, "Display Name Sanitization", "Display names are untrusted metadata. Stripped of bidirectional unicode overrides and control characters to prevent UI spoofing.")
    add_bullet_item(tf_l8, "Formal Models", "Multi-stage key exchange security.", text_color=GRAY_MID, ref_tag="Cohn-Gordon et al., EuroS&P 2017", ref_url="https://eprint.iacr.org/2016/1013")

    tf_r8 = add_card(s8, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="Trust-on-First-Use & Safety Numbers")
    add_bullet_item(tf_r8, "TOFU Binding", "First key seen for a peer is pinned. If a subsequent key differs, sending is blocked and the UI alerts KEY_CHANGED.")
    add_bullet_item(tf_r8, "Safety Number Format", "12 groups of 5 decimal digits (60 digits total) displayed in UI.")
    add_bullet_item(tf_r8, "Deterministic Symmetry", "Computed over SHA-256(minKey || maxKey); identical on both ends regardless of who initiates.")
    add_bullet_item(tf_r8, "Short Authenticated Strings", "Implements Vaudenay's SAS authentication protocol for out-of-band human verification.", ref_tag="Vaudenay, CRYPTO 2005", ref_url="https://www.iacr.org/archive/crypto2005/36210490/36210490.pdf")

    # =========================================================================
    # SLIDE 9: SYSTEM BOUNDARIES & NON-GOALS
    # =========================================================================
    s9 = all_slides[8]
    add_navbar(s9, 8, all_slides)
    add_header(s9, "System Boundaries & Non-Goals", "Honest Security Limitations & Non-Goals", "Explicitly documenting system boundaries and what Tetherless does NOT protect")

    tf_l9 = add_card(s9, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="Architectural Non-Guarantees")
    add_bullet_item(tf_l9, "Relay Observes Metadata", "The relay observes the social graph: who talks to whom, timestamps, frequency, and frame byte lengths.")
    add_bullet_item(tf_l9, "Single Point of Failure", "Centralized relay topology can deny service to clients. True federation is planned for post-1.0.")
    add_bullet_item(tf_l9, "No Group Messaging", "Protocol is strictly point-to-point. Decentralized group messaging requires MLS.", ref_tag="RFC 9420 (MLS)", ref_url="https://datatracker.ietf.org/doc/html/rfc9420")
    add_bullet_item(tf_l9, "No Multi-Device Sync", "One identity per device. A second device has a completely independent identity key.")

    tf_r9 = add_card(s9, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="Cryptographic Scope Boundaries")
    add_bullet_item(tf_r9, "No Per-Message Ratchet", "DH exchange establishes a session key for up to 100,000 messages. Compromise of a session key compromises that session.")
    add_bullet_item(tf_r9, "Session Expiry by Volume", "Key renewal is triggered by message count (100k budget), not by elapsed time TTL. In-memory keys cleared on restart.")
    add_bullet_item(tf_r9, "Live Device Compromise", "Files are encrypted at rest, but an adversary running code inside the live process memory can access decrypted messages.")
    add_bullet_item(tf_r9, "Unaudited Status", "While backed by rigorous automated test suites, Tetherless has not yet undergone third-party commercial security audits.")

    # =========================================================================
    # SLIDE 10: DUMB RELAY & NON-BLOCKING ROUTING
    # =========================================================================
    s10 = all_slides[9]
    add_navbar(s10, 9, all_slides)
    add_header(s10, "Relay Forwarding Architecture", "Dumb Relay: Non-Blocking Ciphertext Routing", "High-throughput message routing with zero payload inspection")

    tf_l10 = add_card(s10, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="Non-Blocking Per-Client Writer Queues")
    add_bullet_item(tf_l10, "Dedicated Writer Threads", "Each client session maintains an ArrayBlockingQueue<byte[]> (capacity 256) serviced by a single dedicated writer thread.")
    add_bullet_item(tf_l10, "No Head-of-Line Blocking", "A slow recipient cannot block the sender's thread. Handlers merely enqueue frames and immediately return.")
    add_bullet_item(tf_l10, "Overflow Protection", "If a recipient queue fills beyond capacity, the connection is terminated cleanly with an ERROR frame rather than deadlocking.")
    add_bullet_item(tf_l10, "Zero Decoding", "Routes based on header fields without decoding payload bytes.")

    tf_r10 = add_card(s10, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="Zero-Knowledge Forwarding")
    add_bullet_item(tf_r10, "Verbatim Forwarding", "Relay forwards frames without re-encoding, preserving cryptographic signatures intact.")
    add_bullet_item(tf_r10, "Redacted Logs", "Logs peer interactions using SHA-256 truncated IDs (Redact.id), never real IDs or payload bytes.")
    add_bullet_item(tf_r10, "Stress Verified", "10 concurrent senders x 1,000 messages (10,000 total) to one recipient delivered without loss or corruption.")

    # =========================================================================
    # SLIDE 11: CONNECTION LIFECYCLE & CONCURRENCY
    # =========================================================================
    s11 = all_slides[10]
    add_navbar(s11, 10, all_slides)
    add_header(s11, "Connection Concurrency & Lifecycle", "Connection Lifecycle & Race Defenses", "Atomic CAS teardown, half-open detection, and clean resource management")

    tf_l11 = add_card(s11, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="Atomic Reconnect (CAS Protection)")
    add_bullet_item(tf_l11, "The Reconnect Race", "A dying socket's cleanup thread racing a new incoming connection previously evicted the active client.")
    add_bullet_item(tf_l11, "CAS Atomic Removal", "ClientRegistry.remove(clientId, expectedSession) removes only if the mapped value matches this exact dying socket instance.")
    add_bullet_item(tf_l11, "Duplicate ID Rejection", "Two clients claiming the same ID are cleanly rejected.")
    add_bullet_item(tf_l11, "Graceful Teardown", "Clients send DISCONNECT on exit; server cleans registry and closes worker threads cleanly.")

    tf_r11 = add_card(s11, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="Heartbeats & Deadlock Prevention")
    add_bullet_item(tf_r11, "Deadlock Detection", "SO_TIMEOUT of 90s prevents orphaned sockets from holding handler threads indefinitely.")
    add_bullet_item(tf_r11, "Heartbeat PING/PONG", "Server issues PING every 30s; closes connection after 2 consecutive missed PONGs.")
    add_bullet_item(tf_r11, "Shutdown Hook", "Relay shutdown hook sends DISCONNECT to all peers and drains thread pool before JVM exit.")
    add_bullet_item(tf_r11, "Leak-Free Proof", "1,000 rapid connect/disconnect cycles leave thread count at baseline (ChatServerLifecycleTest).")

    # =========================================================================
    # SLIDE 12: OPERATIONAL HARDENING & GATING
    # =========================================================================
    s12 = all_slides[11]
    add_navbar(s12, 11, all_slides)
    add_header(s12, "Operational Hardening & Gating", "Operational Hardening & Release Gating", "Eliminating default development keys and securing host environments")

    tf_l12 = add_card(s12, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="Release Gate Enforcement (-PreleaseBuild)")
    add_bullet_item(tf_l12, "The Dev Certificate Trap", "In dev, relay uses a known self-signed cert from repo scripts. Shipping with this would allow trivial MitM by anyone.")
    add_bullet_item(tf_l12, "Refusal to Fall Back", "Packaged relay strictly refuses to start unless a custom keystore is configured; development keystore is stripped from JAR and Docker image.")
    add_bullet_item(tf_l12, "Secret Management", "Prefers KEYSTORE_PASSWORD_FILE over environment variables to prevent leaking credentials in process lists or docker inspect.")

    tf_r12 = add_card(s12, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="Container & Host Sandboxing")
    add_bullet_item(tf_r12, "Docker Compose Hardening", "Runs unprivileged as non-root user (tetherless:tetherless), read-only root filesystem, drop ALL Linux capabilities.")
    add_bullet_item(tf_r12, "Loopback Metrics", "Prometheus metrics on PORT + 1 bound strictly to 127.0.0.1; unauthenticated telemetry is never reachable from WAN.")
    add_bullet_item(tf_r12, "systemd Unit Hardening", "Configured with ProtectSystem=strict, PrivateTmp=true, ProtectHome=true, and system call filtering.")

    # =========================================================================
    # SLIDE 13: CLIENT PERSISTENCE & PACKAGING
    # =========================================================================
    s13 = all_slides[12]
    add_navbar(s13, 12, all_slides)
    add_header(s13, "Client Persistence & Packaging", "Client Implementations: Desktop & Mobile", "Swing UI, SQLite WAL, Room, SQLCipher, and background services")

    tf_l13 = add_card(s13, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="Desktop Client (chat-desktop)")
    add_bullet_item(tf_l13, "EDT Decoupling", "All network I/O and cryptographic operations execute off Event Dispatch Thread. UI updates marshaled via invokeLater.")
    add_bullet_item(tf_l13, "SQLite in WAL Mode", "Configured with PRAGMA journal_mode=WAL and busy_timeout=5000; single-writer connection preventing database lock contention.")
    add_bullet_item(tf_l13, "In-Process Keygen", "Identity keys generated strictly in-process; eliminated legacy flaw that shelled out to keytool exposing passphrases in CLI args.")
    add_bullet_item(tf_l13, "Self-Contained Packaging", "Packaged via jpackage into a standalone .msi on Windows, embedding a stripped custom JRE runtime (users need no JDK).")

    tf_r13 = add_card(s13, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="Mobile Client (chat-mobile)")
    add_bullet_item(tf_r13, "Jetpack Architecture", "Built on Room ORM, ViewModel, LiveData reactive streams, and ListAdapter with DiffUtil.")
    add_bullet_item(tf_r13, "SQLCipher Full-File Encryption", "Unlike desktop column encryption, Android encrypts entire SQLite database file including tables, participants, and timestamps.", ref_tag="SQLCipher Design Spec", ref_url="https://www.zetetic.net/sqlcipher/design/")
    add_bullet_item(tf_r13, "Persistent ChatService", "Configured as an Android Foreground Service with continuous notification; survives backgrounding, Doze mode, and memory trim.")
    add_bullet_item(tf_r13, "Update Checker (CLIENT-DESKTOP-08)", "Async GitHub release polling with non-intrusive UI banner; no dangerous auto-execution of unsigned binaries.")

    # =========================================================================
    # SLIDE 14: ADVERSARIAL VERIFICATION SUITE
    # =========================================================================
    s14 = all_slides[13]
    add_navbar(s14, 13, all_slides)
    add_header(s14, "Adversarial Verification Suite", "Adversarial Testing & Verification Suite", "Proving security properties through deliberate mutation and attack injection")

    tf_l14 = add_card(s14, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="AdversarialRelayTest (13 Attack Scenarios)")
    add_bullet_item(tf_l14, "Key Substitution MITM", "Relay attempts to substitute DH public keys during handshake; clients abort handshake immediately.")
    add_bullet_item(tf_l14, "Identity Forgery", "Relay substitutes public key inside HELLO frame; rejected because Peer ID does not match public key hash.")
    add_bullet_item(tf_l14, "Ciphertext Bit-Flipping", "Single bit flipped in ciphertext payload; GCM authentication tag verification fails, message dropped with warning.")
    add_bullet_item(tf_l14, "Replay Attack", "Relay re-transmits valid captured frame; client detects duplicate counter within sliding window and drops frame.", ref_tag="Perrig et al., TESLA 2002", ref_url="https://doi.org/10.1145/586110.586117")

    tf_r14 = add_card(s14, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="End-to-End & Golden Conformance")
    add_bullet_item(tf_r14, "EndToEndExchangeTest", "Transmits 200 real messages through live relay with external wire tap; mathematically asserts zero plaintext substrings in transit.")
    add_bullet_item(tf_r14, "Cross-Platform Vectors", "17 frozen wire-format vectors (ProtocolVectors) tested across JVM and Android emulator, preventing cross-platform codec drift.")
    add_bullet_item(tf_r14, "Zero-Warning Static Analysis", "SpotBugs with find-sec-bugs plugin runs on all modules with ZERO unresolved high-severity vulnerabilities.")

    # =========================================================================
    # SLIDE 15: ROADMAP & REFERENCES
    # =========================================================================
    s15 = all_slides[14]
    add_navbar(s15, 14, all_slides)
    add_header(s15, "Production Roadmap & References", "MVP Delivery Roadmap & Academic Citations", "Prioritized ticket backlog for production deployment and foundational literature")

    tf_l15 = add_card(s15, Inches(0.8), Inches(1.85), Inches(5.6), Inches(5.1), title="Prioritized MVP Ticket Backlog")
    add_bullet_item(tf_l15, "1. CLIENT-DESKTOP-07 (Active)", "Desktop test coverage: unit tests for MessageRepository and ConnectionManager.")
    add_bullet_item(tf_l15, "2. INTEG-01 (High Priority)", "End-to-end headless integration harness: automated multi-client exchange over live relay.")
    add_bullet_item(tf_l15, "3. BUILD-04 (High Priority)", "Continuous integration: GitHub Actions pipeline running clean build and SpotBugs.")
    add_bullet_item(tf_l15, "4. REL-01 (High Priority)", "Security review pass: formalize threat model, audit logs for zero secret leaks.")
    add_bullet_item(tf_l15, "5. CLIENT-DESKTOP-08 (MVP Feature)", "In-app update notifications: async GitHub release polling with non-intrusive banner.")
    add_bullet_item(tf_l15, "6. REL-03 & INTEG-04 (Release Prep)", "Manual QA matrix execution on Windows and documentation overhaul.")

    tf_r15 = add_card(s15, Inches(6.8), Inches(1.85), Inches(5.7), Inches(5.1), title="Core Academic Bibliography & Standards")
    add_bullet_item(tf_r15, "Diffie & Hellman (1976)", "New Directions in Cryptography. IEEE Transactions on Information Theory.", ref_tag="IEEE", ref_url="https://ieeexplore.ieee.org/document/1055638")
    add_bullet_item(tf_r15, "McGrew & Viega (2004)", "Security of Galois/Counter Mode (GCM). INDOCRYPT / NIST SP 800-38D.", ref_tag="Springer", ref_url="https://doi.org/10.1007/978-3-540-30576-7_27")
    add_bullet_item(tf_r15, "Krawczyk (2010)", "Cryptographic Extraction & HKDF. CRYPTO 2010 / RFC 5869.", ref_tag="IACR", ref_url="https://eprint.iacr.org/2010/264")
    add_bullet_item(tf_r15, "Unger et al. (2015)", "SoK: Secure Messaging. IEEE Symposium on Security and Privacy (S&P).", ref_tag="IEEE S&P", ref_url="https://ieeexplore.ieee.org/document/7163042")
    add_bullet_item(tf_r15, "Cohn-Gordon et al. (2017)", "Formal Analysis of Signal Protocol. IEEE EuroS&P.", ref_tag="IACR", ref_url="https://eprint.iacr.org/2016/1013")
    add_bullet_item(tf_r15, "Vaudenay (2005)", "Secure Communications Based on SAS. CRYPTO 2005.", ref_tag="IACR", ref_url="https://www.iacr.org/archive/crypto2005/36210490/36210490.pdf")
    add_bullet_item(tf_r15, "Frohoff & Lawrence (2015)", "Deserializing Objects Exploit. AppSecCali 2015.", ref_tag="AppSecCali", ref_url="https://frohoff.github.io/appseccali-marshalling-pickles/")
    add_bullet_item(tf_r15, "IETF Standards", "RFC 3526 (DH-14), RFC 5869 (HKDF), RFC 8446 (TLS 1.3), RFC 9420 (MLS).", ref_tag="IETF RFCs", ref_url="https://datatracker.ietf.org/doc/html/rfc8446")

    prs.save(output_path)
    print(f"Monochrome 15-slide presentation generated successfully: {output_path} ({len(prs.slides)} slides)")

if __name__ == "__main__":
    out = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "docs", "tetherless_presentation.pptx"))
    if len(sys.argv) > 1:
        out = sys.argv[1]
    build_monochrome_presentation(out)
