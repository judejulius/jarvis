#!/data/data/com.termux/files/usr/bin/bash
# ==============================================================================
# JARVIS Termux:Boot Setup
# Installs start-jarvis into ~/.termux/boot/ to run Qwen automatically on boot.
# ==============================================================================

BOOT_DIR="$HOME/.termux/boot"
mkdir -p "$BOOT_DIR"

cat << 'EOF' > "$BOOT_DIR/start-jarvis"
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock
sleep 5
bash $HOME/start-jarvis.sh
EOF

chmod +x "$BOOT_DIR/start-jarvis"
cp start-jarvis.sh "$HOME/start-jarvis.sh"
chmod +x "$HOME/start-jarvis.sh"

echo "[✓] Termux:Boot auto-start configured at $BOOT_DIR/start-jarvis"
echo "[✓] To test run now: bash ~/start-jarvis.sh"
