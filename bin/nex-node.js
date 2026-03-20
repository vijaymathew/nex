#!/usr/bin/env node
// Nex Language Command-Line Interface (Node.js)

const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const VERSION = '0.1.1';
const NEX_HOME = process.env.NEX_HOME || path.join(__dirname, '..');

// Helper functions
function showHelp() {
    console.log(`Nex Language v${VERSION} - A high-level language for design and implementation

USAGE:
    nex [COMMAND] [OPTIONS]

COMMANDS:
    (no command)              Start the Nex REPL (requires JVM)
    compile <target> <file>   Compile a Nex file to target language
    format <file|dir>         Format Nex source files (requires JVM)
    doc <file|dir> [output]   Generate documentation from Nex files (requires JVM)
    eval <code>               Evaluate a Nex code snippet
    help                      Show this help message

COMPILE TARGETS:
    javascript                Generate JavaScript source code (requires JVM)
    js                        Alias for javascript

EXAMPLES:
    nex                                      # Start REPL
    nex compile js MyClass.nex               # Compile to JavaScript
    nex format src/                          # Format all files in directory
    nex format MyClass.nex                   # Format single file
    nex doc MyClass.nex MyClass.md           # Generate documentation
    nex eval 'print("Hello, World!")'        # Evaluate code

NOTE:
    This is the Node.js runtime. Some commands (format, doc, REPL) require
    the JVM version. For full functionality, install with: ./install.sh jvm

ENVIRONMENT VARIABLES:
    NEX_HOME                  Nex installation directory (default: auto-detected)

For more information, visit: https://github.com/your-repo/nex`);
}

function showVersion() {
    console.log(`Nex Language v${VERSION} (Node.js)`);
}

// Command implementations
function cmdRepl() {
    console.log('Error: REPL requires JVM installation');
    console.log('Install JVM version with: ./install.sh jvm');
    process.exit(1);
}

function cmdCompile(target, file) {
    console.log('Error: Compile requires JVM installation');
    console.log('The Node.js runtime can only execute pre-parsed AST.');
    console.log('Install JVM version with: ./install.sh jvm');
    process.exit(1);
}

function cmdFormat(filePath) {
    console.log('Error: Format requires JVM installation');
    console.log('Install JVM version with: ./install.sh jvm');
    process.exit(1);
}

function cmdDoc(input, output) {
    console.log('Error: Doc generation requires JVM installation');
    console.log('Install JVM version with: ./install.sh jvm');
    process.exit(1);
}

function cmdEval(code) {
    if (!code) {
        console.error('Error: eval requires a code argument');
        console.error('Usage: nex eval \'<code>\'');
        process.exit(1);
    }

    // Load the Nex interpreter
    const nexWrapper = require(path.join(NEX_HOME, 'nex-wrapper.js'));

    try {
        // Note: This only works with pre-parsed AST
        // For now, show a message about limitations
        console.log('Error: Direct code evaluation requires JVM for parsing');
        console.log('The Node.js runtime can only execute pre-parsed AST.');
        console.log('');
        console.log('To evaluate Nex code:');
        console.log('  1. Install JVM version: ./install.sh jvm');
        console.log('  2. Use: nex eval \'<code>\'');
        process.exit(1);
    } catch (e) {
        console.error('Error evaluating code:', e.message);
        process.exit(1);
    }
}

// Main command dispatcher
function main() {
    const args = process.argv.slice(2);
    const command = args[0] || 'repl';

    switch (command) {
        case 'repl':
            cmdRepl();
            break;
        case 'compile':
            cmdCompile(args[1], args[2]);
            break;
        case 'format':
            cmdFormat(args[1]);
            break;
        case 'doc':
            cmdDoc(args[1], args[2]);
            break;
        case 'eval':
            cmdEval(args[1]);
            break;
        case 'help':
        case '--help':
        case '-h':
            showHelp();
            break;
        case 'version':
        case '--version':
        case '-v':
            showVersion();
            break;
        default:
            console.error(`Error: Unknown command '${command}'`);
            console.error('Run \'nex help\' for usage information.');
            process.exit(1);
    }
}

main();
