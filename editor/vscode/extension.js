// Nex for VS Code: starts `nex lsp` for errors, formatting and the outline,
// and drives a `nex` REPL in the integrated terminal.

const path = require('path');
const vscode = require('vscode');
const { LanguageClient } = require('vscode-languageclient/node');

let client;
let repl;

function settings() {
  return vscode.workspace.getConfiguration('nex');
}

function nexCommand() {
  return settings().get('path') || 'nex';
}

// The directory `nex` runs in: interns named by path are looked up from it.
function workingDirectory(uri) {
  const folder = uri && vscode.workspace.getWorkspaceFolder(uri);
  if (folder) return folder.uri.fsPath;
  const first = vscode.workspace.workspaceFolders && vscode.workspace.workspaceFolders[0];
  if (first) return first.uri.fsPath;
  if (uri && uri.scheme === 'file') return path.dirname(uri.fsPath);
  return undefined;
}

// --- Language server -------------------------------------------------------

async function startLanguageServer() {
  if (!settings().get('languageServer.enable')) return;
  const active = vscode.window.activeTextEditor;
  const serverOptions = {
    command: nexCommand(),
    args: ['lsp'],
    options: {
      cwd: workingDirectory(active && active.document.uri),
      // `nex` is a shell script; Windows needs a shell to find and run it.
      shell: process.platform === 'win32'
    }
  };
  const clientOptions = {
    documentSelector: [
      { scheme: 'file', language: 'nex' },
      { scheme: 'untitled', language: 'nex' }
    ],
    outputChannelName: 'Nex Language Server'
  };
  client = new LanguageClient('nex', 'Nex Language Server', serverOptions, clientOptions);
  try {
    await client.start();
  } catch (err) {
    client = undefined;
    const choice = await vscode.window.showErrorMessage(
      `Nex: could not start \`${nexCommand()} lsp\` (${err.message || err}). ` +
        'Install Nex, or set "nex.path" to the full path of the nex command.',
      'Open Settings'
    );
    if (choice === 'Open Settings') {
      vscode.commands.executeCommand('workbench.action.openSettings', 'nex.path');
    }
  }
}

async function stopLanguageServer() {
  if (!client) return;
  const old = client;
  client = undefined;
  try {
    await old.stop();
  } catch (_) {
    // Already gone.
  }
}

async function restartLanguageServer() {
  await stopLanguageServer();
  await startLanguageServer();
}

// --- REPL ------------------------------------------------------------------

function ensureRepl(uri) {
  if (repl && repl.exitStatus === undefined) return repl;
  repl = vscode.window.createTerminal({
    name: 'Nex REPL',
    shellPath: nexCommand(),
    cwd: workingDirectory(uri)
  });
  return repl;
}

function startRepl() {
  const editor = vscode.window.activeTextEditor;
  ensureRepl(editor && editor.document.uri).show();
}

// The REPL reads one line at a time and keeps reading while a block is
// open, so text is sent to it as typed lines.
function sendToRepl(text, uri) {
  const terminal = ensureRepl(uri);
  terminal.show(true);
  terminal.sendText(text.replace(/\r\n/g, '\n').replace(/\n+$/, ''), true);
}

function sendSelection() {
  const editor = vscode.window.activeTextEditor;
  if (!editor) return;
  const doc = editor.document;
  const sel = editor.selection;
  if (sel.isEmpty) {
    // No selection: send the current line and move to the next one.
    const line = doc.lineAt(sel.active.line);
    if (!line.isEmptyOrWhitespace) sendToRepl(line.text, doc.uri);
    const next = Math.min(sel.active.line + 1, doc.lineCount - 1);
    const pos = new vscode.Position(next, doc.lineAt(next).firstNonWhitespaceCharacterIndex);
    editor.selection = new vscode.Selection(pos, pos);
    editor.revealRange(new vscode.Range(pos, pos));
  } else {
    sendToRepl(doc.getText(sel), doc.uri);
  }
}

async function savedFile() {
  const editor = vscode.window.activeTextEditor;
  if (!editor || editor.document.languageId !== 'nex') return undefined;
  const doc = editor.document;
  if (doc.isUntitled) {
    vscode.window.showWarningMessage('Nex: save the file first.');
    return undefined;
  }
  if (doc.isDirty && !(await doc.save())) return undefined;
  return doc.uri;
}

async function loadFile() {
  const uri = await savedFile();
  if (uri) sendToRepl(`:load ${uri.fsPath}`, uri);
}

// --- Running a file --------------------------------------------------------

async function runFile() {
  const uri = await savedFile();
  if (!uri) return;
  const task = new vscode.Task(
    { type: 'nex', file: uri.fsPath },
    vscode.workspace.getWorkspaceFolder(uri) || vscode.TaskScope.Workspace,
    `run ${path.basename(uri.fsPath)}`,
    'nex',
    new vscode.ProcessExecution(nexCommand(), [uri.fsPath], { cwd: workingDirectory(uri) })
  );
  task.presentationOptions = {
    reveal: vscode.TaskRevealKind.Always,
    focus: false,
    clear: true,
    panel: vscode.TaskPanelKind.Dedicated
  };
  await vscode.tasks.executeTask(task);
}

// --- Activation ------------------------------------------------------------

async function activate(context) {
  context.subscriptions.push(
    vscode.commands.registerCommand('nex.runFile', runFile),
    vscode.commands.registerCommand('nex.startRepl', startRepl),
    vscode.commands.registerCommand('nex.sendSelection', sendSelection),
    vscode.commands.registerCommand('nex.loadFile', loadFile),
    vscode.commands.registerCommand('nex.restartLanguageServer', restartLanguageServer),
    vscode.window.onDidCloseTerminal((t) => {
      if (t === repl) repl = undefined;
    }),
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration('nex.path') || e.affectsConfiguration('nex.languageServer')) {
        restartLanguageServer();
      }
    })
  );
  await startLanguageServer();
}

function deactivate() {
  return stopLanguageServer();
}

module.exports = { activate, deactivate };
