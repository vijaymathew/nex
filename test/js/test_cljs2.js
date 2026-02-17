// Debug test to see how evalNode works
const nex = require('../../target/nex.js');

console.log('Exported properties:');
for (let key in nex) {
  console.log(`  ${key}: ${typeof nex[key]}`);
}

console.log('\nevalNode properties:');
for (let key in nex.evalNode) {
  console.log(`  ${key}: ${typeof nex.evalNode[key]}`);
}

console.log('\nTrying to call evalNode:');
const ctx = nex.makeContext();

const simpleAst = {
  type: 'program',
  imports: [],
  interns: [],
  classes: [],
  calls: []
};

try {
  // Try calling as a function
  const result1 = nex.evalNode(ctx, simpleAst);
  console.log('Direct call worked!');
} catch (e1) {
  console.log('Direct call failed:', e1.message);

  // Try calling with .call
  try {
    const result2 = nex.evalNode.call(null, ctx, simpleAst);
    console.log('.call() worked!');
  } catch (e2) {
    console.log('.call() failed:', e2.message);

    // Try if it has a callable method
    if (typeof nex.evalNode.invoke === 'function') {
      try {
        const result3 = nex.evalNode.invoke(ctx, simpleAst);
        console.log('.invoke() worked!');
      } catch (e3) {
        console.log('.invoke() failed:', e3.message);
      }
    }
  }
}
