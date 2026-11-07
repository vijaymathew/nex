# Sorting with a comparator function: 2M pseudo-random integers.
import time
from functools import cmp_to_key
def run(n):
    xs, seed = [], 12345
    for _ in range(n):
        seed = (seed * 1103515245 + 12345) % 2147483648
        xs.append(seed)
    ys = sorted(xs, key=cmp_to_key(lambda a, b: (a > b) - (a < b)))
    total = 0
    for y in ys:
        total = (total + y) % 1000003
    return total
t0 = time.perf_counter_ns()
r = run(2000000)
t1 = time.perf_counter_ns()
print(r)
print((t1 - t0) // 1000)
