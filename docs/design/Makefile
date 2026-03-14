BOOK_DIR := _book
TITLE_SLUG := design-and-implementation-of-nex

.PHONY: html pdf epub book clean

html:
	XDG_CACHE_HOME=$(PWD)/.cache quarto render --to html

pdf:
	XDG_CACHE_HOME=$(PWD)/.cache quarto render --to pdf

epub:
	XDG_CACHE_HOME=$(PWD)/.cache quarto render --to epub

book: html pdf epub

clean:
	rm -rf $(BOOK_DIR) .quarto .cache
