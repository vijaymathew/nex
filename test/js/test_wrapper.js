// Test the JavaScript wrapper

const nex = require('../../nex-wrapper.js');

console.log('Testing Nex JavaScript Wrapper\n');

// Create a context
console.log('1. Creating context...');
const ctx = nex.makeContext();
console.log('   ✓ Context created\n');

// Define a simple class
const pointClass = {
  type: 'class',
  name: 'Point',
  'generic-params': [],
  parents: [],
  body: {
    public: {
      fields: [
        { name: 'x', type: 'Integer', note: null },
        { name: 'y', type: 'Integer', note: null }
      ],
      methods: []
    },
    private: { fields: [], methods: [] },
    selective: []
  },
  constructors: [
    {
      name: 'make',
      params: [
        { name: 'a', type: 'Integer' },
        { name: 'b', type: 'Integer' }
      ],
      body: [
        {
          type: 'let',
          target: 'x',
          value: { type: 'identifier', name: 'a' }
        },
        {
          type: 'let',
          target: 'y',
          value: { type: 'identifier', name: 'b' }
        }
      ],
      require: null,
      ensure: null
    }
  ],
  invariant: []
};

// Register the class
console.log('2. Registering Point class...');
nex.registerClass(ctx, 'Point', pointClass);
console.log('   ✓ Class registered\n');

// Evaluate a program
console.log('3. Evaluating program AST...');
const programAst = {
  type: 'program',
  imports: [],
  interns: [],
  classes: [pointClass],
  calls: []
};

const ctx2 = nex.evalProgram(programAst);
console.log('   ✓ Program evaluated\n');

console.log('✓ All tests passed!');
console.log('\nThe Nex ClojureScript interpreter is ready for use.');
console.log('See docs/CLOJURESCRIPT.md for more information.');
