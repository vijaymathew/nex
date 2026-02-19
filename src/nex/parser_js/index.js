// CommonJS wrapper for ANTLR4 ES6 modules
// This allows shadow-cljs to require the parser

async function loadParser() {
  const antlr4 = await import('antlr4');
  const { default: nexlangLexer } = await import('./grammar/nexlangLexer.js');
  const { default: nexlangParser } = await import('./grammar/nexlangParser.js');

  return { antlr4: antlr4.default, nexlangLexer, nexlangParser };
}

module.exports = { loadParser };
