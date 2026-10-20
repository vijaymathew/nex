# A Next-Letter Predictor in Nex

Type the start of a word, and a small neural network suggests the next
letter. Accept it, and it suggests the next one, until it decides the word
is complete.

```
start of word> app
  app[r]   (23%)      others: e 19%, l 17%, o 15%, i 9%
  > l                                  <- type "l" yourself to steer it
  appl[e]   (45%)      others: i 19%, a 13%, y 11%, o 8%
  >                                    <- Enter accepts "e"
  apple[a]   (21%)      others: s 18%, r 11%, end 7%, n 7%
  > n                                  <- "n" shows the next option instead
  apple[s]   (18%)      others: a 21%, r 11%, end 7%, n 7%
  >                                    <- Enter accepts "s"
  apples   -> looks complete (42%)      others: s 19%, t 15%, e 4%, h 4%
  >                                    <- Enter accepts "end of word"
  ==> apples
```

No machine-learning library is used. Every step is written out in Nex:
matrix multiplication, softmax, backpropagation, gradient descent, and even
the random number generator. Contracts check the shapes and the math as it
runs.

The tutorial `tutorial/neural_network_tutorial.pdf` builds this whole
project step by step, explaining the math along the way.

## Quick start

```bash
cd examples/neural_network
nex checks.nex        # 33 checks, including a gradient check of backprop
nex train.nex         # train (about 3 minutes), writes model.json
nex play.nex          # interactive word completion
```

`train.nex` reads `/usr/share/dict/words`. To use another word list, pass
any text file with one word per line:

```bash
nex train.nex --words mywords.txt
```

Run these on the default (compiled) backend. The `--interpret` backend works
too, but it is far too slow for training.

## Files

| File | What it does |
|---|---|
| `helpers.nex` | Printing numbers neatly, and resolving file names given on the command line |
| `vector_math.nex` | `dot`, `affine` (W·x + b), `add_scaled`, `tanh`, `softmax`, `argmax` |
| `rng.nex` | `Rng`, a seedable random number generator with normal (Gaussian) numbers, and `shuffle` |
| `vocabulary.nex` | The 27 symbols: `.` (padding and end of word) and `a`..`z` |
| `next_letter_model.nex` | The network: embeddings, hidden layer, softmax, forward pass, backpropagation, gradient-descent update, save and load |
| `training_data.nex` | Turns words into training examples; cross-entropy loss and evaluation |
| `train.nex` | The training program |
| `play.nex` | Interactive completion, or one-shot completion of prefixes given on the command line |
| `checks.nex` | Executable checks for all of the above |

## Using `play.nex`

```bash
nex play.nex                       # interactive
nex play.nex app thin walk qu      # complete each prefix and exit
nex play.nex --model other.json    # use another model file
```

After each suggestion in interactive mode:

| Key | Effect |
|---|---|
| Enter | accept the suggested letter (or, at "looks complete", finish the word) |
| `n` | show the next most likely letter instead (cycles through the top 5) |
| letters | type one or more letters yourself, then the model continues from there |
| `q` | abandon this word and start a new one |

An empty line at the `start of word>` prompt, or Ctrl-D, quits.

One-shot mode always takes the model's top choice:

```
$ nex play.nex app thin walk qu
         app -> apprate         (next letter: r 23%, e 19%, l 17%)
        thin -> thing           (next letter: g 72%, e 5%, d 5%)
        walk -> walking         (next letter: i 26%, e 24%, s 19%)
          qu -> quint           (next letter: i 27%, a 23%, e 19%)
```

Always taking the top choice often produces word-like non-words
("apprate"). Interactive mode, where you steer, works better.

## Using `train.nex`

```bash
nex train.nex [--words FILE] [--steps N] [--out FILE] ...
```

| Option | Default | Meaning |
|---|---|---|
| `--words` | `/usr/share/dict/words` | word list, one per line. Only all-lowercase a-z words of 2+ letters are used |
| `--out` | `model.json` | where to save the trained model |
| `--steps` | 20000 | number of minibatch updates |
| `--batch` | 32 | minibatch size |
| `--lr` | 0.3 | learning rate. It drops to a tenth for the last 25% of steps |
| `--context` | 4 | how many previous letters the model looks at |
| `--emb` | 8 | size of each letter's embedding vector |
| `--hidden` | 64 | number of neurons in the hidden layer |
| `--seed` | 42 | random seed, so runs can be repeated |

The model is saved at every progress report, so if you stop training early
with Ctrl-C, you keep the model as of the last report.

A default run on a 104,000-line system dictionary:

```
57465 training words -> 533799 examples; 6384 validation words
model has 4083 parameters (context=4, emb=8, hidden=64)
before training: val loss 3.297 (guessing uniformly would give 3.296)

step   1000/20000  lr 0.300  train loss 2.526  val loss 2.392  val accuracy 27.5%  [   10s]
...
step  15000/20000  lr 0.300  train loss 2.255  val loss 2.212  val accuracy 31.2%  [  127s]
step  16000/20000  lr 0.030  train loss 2.153  val loss 2.132  val accuracy 32.3%  [  138s]
...
step  20000/20000  lr 0.030  train loss 2.130  val loss 2.118  val accuracy 33.1%  [  174s]
```

- **loss** is the average cross-entropy, `-ln(probability given to the right
  letter)`. Lower is better. Guessing uniformly among 27 symbols scores
  ln 27 = 3.30.
- **val** numbers come from 10% of the words, held back and never trained
  on. If train loss kept falling while val loss rose, the model would be
  overfitting.
- **val accuracy** is how often the top suggestion is exactly right. Even a
  perfect model can't get close to 100%, because after "app" both "l" and
  "r" are legitimate.
- Notice the jump at step 16000, where the learning rate drops. Big steps
  were bouncing around the bottom of the valley; smaller ones let the model
  settle into it.

If you change `--context`, `--emb` or `--hidden`, the sizes are saved in
`model.json` and `play.nex` picks them up.

## How it works

### Training data comes free

Every word supplies its own answers. With a context of 4, and `.` as both
padding and the end-of-word marker, `apple` gives six examples:

```
"...."  ->  a
"...a"  ->  p
"..ap"  ->  p
".app"  ->  l
"appl"  ->  e
"pple"  ->  .      <- this is how the model learns when a word ends
```

### The network

```
 last 4 letters        embeddings           hidden layer          output
 [., a, p, p]  ---->  4 x 8 numbers  ---->  64 neurons     ---->  27 probabilities
               lookup in C          tanh(W1·x + b1)     softmax(W2·h + b2)
```

- **Embedding:** each of the 27 symbols has a learned vector of 8 numbers
  (a row of matrix `C`). The 4 context letters' vectors are joined into one
  input vector of 32 numbers.
- **Hidden layer:** `h = tanh(W1·x + b1)`.
- **Output layer:** `W2·h + b2` gives 27 raw scores (logits). Softmax turns
  them into probabilities.

That's 4,083 parameters in total.

### Training

Each step:

1. **Forward pass:** run 32 random examples through the network.
2. **Loss:** cross-entropy, `-ln(probability given to the right letter)`.
3. **Backpropagation:** apply the chain rule backwards through the layers
   (output, then tanh, then hidden, then embeddings) to get the gradient for
   every parameter. Each step is written out in `Next_Letter_Model.backward`,
   and `checks.nex` verifies it against gradients measured numerically.
4. **Update:** `p := p - lr * gradient`, for every parameter.

## Compatibility with the Python version

This is a translation of a pure-Python project. `model.json` uses the same
format, so a model trained by either version can be loaded by the other, and
on the same model both give the same predictions. The random number
generators differ, so a training run with the same seed does not give the
same model.

## Limitations

- **It only ever sees the last 4 letters.** In a long word, it has forgotten
  how the word began. Raise `--context` to see more, at the cost of slower
  training.
- **It knows spelling patterns, not word frequency.** A dictionary lists
  "apple" once and "appraise" once, so the model doesn't know that "apple"
  is the more common word. Many more dictionary words start with "appr..."
  and "appe..." than with "appl...", so `r` and `e` really are reasonable
  guesses after "app".
- **It can invent plausible non-words.** It learns what English words look
  like rather than memorizing a list. This is the same kind of behavior that
  makes large language models "hallucinate".

## Ideas to extend it

- Try `--hidden 128` or `--context 6` and compare the val loss.
- Swap `tanh` for ReLU (`v.max(0.0)`, with derivative 0 or 1) in `forward`
  and `backward`, and compare.
- Add **temperature**: divide the logits by T before softmax. T < 1 makes
  suggestions more confident, and T > 1 makes them more adventurous.
- Add a second hidden layer, and watch how much more backprop code it takes.
