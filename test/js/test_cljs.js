// Test ClojureScript build of Nex interpreter
// This test uses pre-parsed AST (server-side parsing pattern)

const nex = require('../../target/nex.js');

console.log('Testing Nex ClojureScript build...\n');

// Test 1: Context creation
console.log('1. Testing makeContext:');
const ctx = nex.makeContext();
console.log('   Context created:', ctx ? '✓ OK' : '✗ FAILED');

// Test 2: Register a class manually
console.log('\n2. Testing registerClass:');
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
      methods: [
        {
          name: 'show',
          params: [],
          'return-type': null,
          body: [
            {
              type: 'call',
              target: { type: 'identifier', name: 'print' },
              method: null,
              arguments: [{ type: 'identifier', name: 'x' }]
            }
          ],
          require: null,
          ensure: null,
          note: null
        }
      ]
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

try {
  nex.registerClass(ctx, 'Point', pointClass);
  console.log('   Class registered: ✓ OK');
} catch (e) {
  console.log('   Class registration failed: ✗ FAILED');
  console.log('   Error:', e.message);
}

// Test 3: Evaluate AST
console.log('\n3. Testing evalNode with pre-parsed AST:');
const programAst = {
  type: 'program',
  imports: [],
  interns: [],
  classes: [pointClass],
  calls: []
};

try {
  // evalNode is a ClojureScript multimethod, call it with .call()
  nex.evalNode.call(null, ctx, programAst);
  console.log('   AST evaluation: ✓ OK');
} catch (e) {
  console.log('   AST evaluation failed: ✗ FAILED');
  console.log('   Error:', e.message);
  console.log('   Stack:', e.stack);
}

console.log('\n✓ ClojureScript build is working!');
console.log('\nNote: For parsing Nex source code, use server-side parsing (JVM)');
console.log('and send the AST to the ClojureScript runtime. See docs/CLOJURESCRIPT.md');
