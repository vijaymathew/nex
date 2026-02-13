(import (srfi-1))

(define (nex-tokenize input)
  (define len (string-length input))
  (define pos 0)

  (define (peek)
    (if (< pos len) (string-ref input pos) #\nul))

  (define (advance)
    (set! pos (+ pos 1)))

  (define (skip-whitespace)
    (when (and (< pos len)
               (char-whitespace? (peek)))
      (advance)
      (skip-whitespace)))

  (define (collect pred)
    (let ((start pos))
      (let loop ()
        (if (and (< pos len) (pred (peek)))
            (begin (advance) (loop))))
      (substring input start pos)))

  (define (identifier? c)
    (char-alphabetic? c))

  (define (identifier-part? c)
    (or (char-alphabetic? c)
        (char-numeric? c)
        (char=? c #\_)))

  (define (read-identifier)
    (let ((name (collect identifier-part?)))
      (cons 'IDENT name)))

  (define operators
    '("=" "/=" "<" "<=" ">" ">=" "+" "-" "*" "/" "."))

  (define (read-operator)
    (let* ((c (peek)))
      (advance)
      (let ((next (peek)))
        (cond
          ((and (char=? c #\/) (char=? next #\=))
           (advance) (cons 'OP "/="))
          ((and (char=? c #\<) (char=? next #\=))
           (advance) (cons 'OP "<="))
          ((and (char=? c #\>) (char=? next #\=))
           (advance) (cons 'OP ">="))
          (else
           (cons 'OP (string c)))))))

  (define (read-string)
    (advance) ; skip opening "
    (let loop ((chars '()))
      (let ((c (peek)))
        (cond
          ((char=? c #\") (advance)
           (cons 'STRING (list->string (reverse chars))))
          ((char=? c #\\)
           (advance)
           (let ((e (peek)))
             (advance)
             (loop (cons
                    (cond
                      ((char=? e #\n) #\newline)
                      ((char=? e #\t) #\tab)
                      ((char=? e #\\) #\\)
                      ((char=? e #\") #\")
                      (else e))
                    chars))))
          (else
           (advance)
           (loop (cons c chars)))))))

  (define (read-char-literal)
    (advance) ; skip $
    (let ((c (peek)))
      (advance)
      (let ((value
             (if (char=? c #\\)
                 (let ((e (peek)))
                   (advance)
                   (char->integer
                    (cond
                      ((char=? e #\n) #\newline)
                      ((char=? e #\t) #\tab)
                      (else e))))
                 (char->integer c))))
        (cons 'CHAR value))))

  (define (remove-underscores s)
    (list->string
     (filter (lambda (c) (not (char=? c #\_)))
             (string->list s))))

  (define (read-number)
    (let ((start pos))
      (let loop ()
        (if (and (< pos len)
                 (or (char-numeric? (peek))
                     (char=? (peek) #\.)
                     (char=? (peek) #\e)
                     (char=? (peek) #\E)
                     (char=? (peek) #\#)
                     (char=? (peek) #\_)
                     (char-alphabetic? (peek))
                     (char=? (peek) #\+)
                     (char=? (peek) #\-)))
            (begin (advance) (loop))))
      (let* ((raw (substring input start pos))
             (clean (remove-underscores raw)))
        (cond
          ;; Base notation
          ((string-contains clean #\#)
           (let* ((parts (string-split clean #\#))
                  (base (string->number (car parts)))
                  (value (cadr parts)))
             (cons 'INTEGER (string->number value base))))
          ;; Float
          ((string-contains clean #\.)
           (cons 'FLOAT (string->number clean)))
          ;; Integer
          (else
           (cons 'INTEGER (string->number clean)))))))

  ;; Utility
  (define (string-contains s ch)
    (let loop ((i 0))
      (cond
        ((= i (string-length s)) #f)
        ((char=? (string-ref s i) ch) #t)
        (else (loop (+ i 1))))))

  (define (string-split s ch)
    (let loop ((i 0) (start 0) (acc '()))
      (if (= i (string-length s))
          (reverse (cons (substring s start i) acc))
          (if (char=? (string-ref s i) ch)
              (loop (+ i 1) (+ i 1)
                    (cons (substring s start i) acc))
              (loop (+ i 1) start acc)))))

  ;; Main loop
  (define (next-token)
    (skip-whitespace)
    (if (>= pos len)
        #f
        (let ((c (peek)))
          (cond
            ((identifier? c) (read-identifier))
            ((char=? c #\") (read-string))
            ((char=? c #\$) (read-char-literal))
            ((or (char-numeric? c)
                 (and (char=? c #\-) 
                      (< (+ pos 1) len)
                      (char-numeric? (string-ref input (+ pos 1)))))
             (read-number))
            ((member (string c) operators)
             (read-operator))
            (else (advance) (next-token))))))

  (let loop ((tokens '()))
    (let ((tok (next-token)))
      (if tok
          (loop (cons tok tokens))
          (reverse tokens)))))
