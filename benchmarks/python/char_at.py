# Indexing a string: the character at every position of a 9,000-character
# string, an ASCII string in Nex, 10,000 times over (90M lookups), summing the code
# points. Python strings index by code point natively.
import time
def run(passes):
    s = "abcdefghi" * 1000
    n = len(s)
    total = 0
    for _ in range(passes):
        for i in range(n):
            total += ord(s[i])
    return total
t0 = time.perf_counter_ns()
r = run(10000)
t1 = time.perf_counter_ns()
print(r)
print((t1 - t0) // 1000)
