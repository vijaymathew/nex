.PHONY: book html pdf epub print clean init-texmf ensure-pdflatex

QUARTO ?= quarto
CACHE_DIR := $(CURDIR)/.cache
TEXMF_VAR := $(CACHE_DIR)/texmf-var
TEXMF_CONFIG := $(CACHE_DIR)/texmf-config
TEXMF_HOME := $(CACHE_DIR)/texmf-home
RENDER_ENV := XDG_CACHE_HOME=$(CACHE_DIR) TEXMFVAR=$(TEXMF_VAR) TEXMFCONFIG=$(TEXMF_CONFIG) TEXMFHOME=$(TEXMF_HOME)
INTERMEDIATE_STEM := Programming-with-Nex

init-texmf:
	@mkdir -p $(CACHE_DIR) $(TEXMF_VAR) $(TEXMF_CONFIG) $(TEXMF_HOME)
	@if [ ! -f "$(TEXMF_HOME)/tlpkg/texlive.tlpdb" ]; then \
		$(RENDER_ENV) tlmgr init-usertree; \
	fi

ensure-pdflatex:
	@if ! command -v pdflatex >/dev/null 2>&1; then \
		echo "pdflatex not found; installing TinyTeX via Quarto..."; \
		$(RENDER_ENV) $(QUARTO) install tinytex --no-prompt; \
	fi

book: init-texmf ensure-pdflatex
	$(RENDER_ENV) $(QUARTO) render
	@epub_file=$$(ls -1 _book/*.epub 2>/dev/null | head -n1); \
	if [ -n "$$epub_file" ]; then cp "$$epub_file" _book/programming-with-nex.epub; fi
	@pdf_file=$$(ls -1 _book/*.pdf 2>/dev/null | head -n1); \
	if [ -n "$$pdf_file" ]; then cp "$$pdf_file" _book/programming-with-nex.pdf; fi
	@rm -f _book/$(INTERMEDIATE_STEM).pdf _book/$(INTERMEDIATE_STEM).epub

html: init-texmf
	$(RENDER_ENV) $(QUARTO) render --to html

pdf: init-texmf ensure-pdflatex
	$(RENDER_ENV) $(QUARTO) render --to pdf
	@pdf_file=$$(ls -1 _book/*.pdf 2>/dev/null | head -n1); \
	if [ -n "$$pdf_file" ]; then cp "$$pdf_file" _book/programming-with-nex.pdf; fi
	@rm -f _book/$(INTERMEDIATE_STEM).pdf

epub: init-texmf
	$(RENDER_ENV) $(QUARTO) render --to epub
	@epub_file=$$(ls -1 _book/*.epub 2>/dev/null | head -n1); \
	if [ -n "$$epub_file" ]; then cp "$$epub_file" _book/programming-with-nex.epub; fi
	@rm -f _book/$(INTERMEDIATE_STEM).epub

print: init-texmf ensure-pdflatex
	$(RENDER_ENV) $(QUARTO) render --profile print --to pdf
	@print_pdf=$$(ls -1 _book/print/*.pdf 2>/dev/null | head -n1); \
	if [ -n "$$print_pdf" ]; then cp "$$print_pdf" _book/print/programming-with-nex-print.pdf; fi
	@rm -f _book/print/$(INTERMEDIATE_STEM).pdf
	@rm -f _book/$(INTERMEDIATE_STEM).pdf _book/$(INTERMEDIATE_STEM).epub

clean:
	rm -rf _book .cache .quarto
