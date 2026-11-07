# Recursive calls: fib(35).
import time
def fib(n):
    return n if n < 2 else fib(n - 1) + fib(n - 2)
t0 = time.perf_counter_ns()
r = fib(35)
t1 = time.perf_counter_ns()
print(r)
print((t1 - t0) // 1000)
