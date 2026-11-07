# Method calls on one object (monomorphic): 20M.
import time
class Counter:
    def __init__(self, b):
        self.bias = b
    def step(self, x):
        return x + self.bias
def run(n):
    c, total = Counter(3), 0
    for i in range(n):
        total = (total + c.step(i)) % 1000003
    return total
t0 = time.perf_counter_ns()
r = run(20000000)
t1 = time.perf_counter_ns()
print(r)
print((t1 - t0) // 1000)
