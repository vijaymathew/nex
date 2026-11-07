# String concatenation: 5M strings built from a literal and a number.
import time
def run(n):
    total = 0
    for i in range(n):
        total += len("k" + str(i))
    return total
t0 = time.perf_counter_ns()
r = run(5000000)
t1 = time.perf_counter_ns()
print(r)
print((t1 - t0) // 1000)
