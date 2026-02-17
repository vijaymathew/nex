#!/bin/bash
# Download ANTLR4 JAR if not present
# This is only needed for generating JavaScript parsers (advanced use case)

ANTLR_VERSION="4.13.1"
ANTLR_JAR="antlr-${ANTLR_VERSION}-complete.jar"
ANTLR_URL="https://www.antlr.org/download/${ANTLR_JAR}"

if [ -f "$ANTLR_JAR" ]; then
    echo "✓ ANTLR JAR already exists: $ANTLR_JAR"
    exit 0
fi

echo "Downloading ANTLR ${ANTLR_VERSION}..."
curl -O "$ANTLR_URL"

if [ $? -eq 0 ]; then
    echo "✓ Downloaded $ANTLR_JAR"
else
    echo "✗ Failed to download ANTLR JAR"
    exit 1
fi
