/**
 * Nex Language JavaScript Wrapper
 *
 * This wrapper provides a more JavaScript-friendly API for the Nex interpreter.
 * The ClojureScript build exports multimethods which need special handling.
 */

const nexCljs = require('./target/nex.js');

/**
 * Create a new Nex interpreter context.
 * @returns {Object} A new context for evaluating Nex code
 */
function makeContext() {
  return nexCljs.makeContext();
}

/**
 * Evaluate a Nex AST node in the given context.
 *
 * Note: This requires a pre-parsed AST. Use server-side parsing (JVM)
 * to convert Nex source code to AST, or manually construct AST objects.
 *
 * @param {Object} ctx - The interpreter context
 * @param {Object} ast - The AST node to evaluate
 * @returns {*} The result of evaluating the AST
 */
function evalNode(ctx, ast) {
  // evalNode is a ClojureScript multimethod, call it with .call()
  return nexCljs.evalNode.call(null, ctx, ast);
}

/**
 * Register a class definition in the given context.
 *
 * @param {Object} ctx - The interpreter context
 * @param {string} className - The name of the class
 * @param {Object} classDef - The class definition (AST format)
 * @returns {*} The result of registration
 */
function registerClass(ctx, className, classDef) {
  return nexCljs.registerClass(ctx, className, classDef);
}

/**
 * Evaluate a complete Nex program from AST.
 *
 * @param {Object} ast - The program AST (must have type: 'program')
 * @returns {Object} A new context with the program evaluated
 */
function evalProgram(ast) {
  const ctx = makeContext();
  evalNode(ctx, ast);
  return ctx;
}

module.exports = {
  makeContext,
  evalNode,
  registerClass,
  evalProgram,
  // Export the raw ClojureScript module for advanced use
  _cljs: nexCljs
};
