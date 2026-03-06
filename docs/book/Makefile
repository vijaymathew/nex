.PHONY: book html pdf epub print cover clean

QUARTO ?= quarto
CACHE_DIR := $(CURDIR)/.cache
TEXMF_VAR := $(CACHE_DIR)/texmf-var
TEXMF_CONFIG := $(CACHE_DIR)/texmf-config
TEXMF_HOME := $(CACHE_DIR)/texmf-home
RENDER_ENV := XDG_CACHE_HOME=$(CACHE_DIR) TEXMFVAR=$(TEXMF_VAR) TEXMFCONFIG=$(TEXMF_CONFIG) TEXMFHOME=$(TEXMF_HOME)
INTERMEDIATE_STEM := Beyond-Code----Building-Software-Systems-That-Last

book:
	@mkdir -p $(CACHE_DIR) $(TEXMF_VAR) $(TEXMF_CONFIG) $(TEXMF_HOME)
	$(RENDER_ENV) $(QUARTO) render
	@epub_file=$$(ls -1 _book/*.epub 2>/dev/null | head -n1); \
	if [ -n "$$epub_file" ]; then cp "$$epub_file" _book/beyond-code.epub; fi
	@pdf_file=$$(ls -1 _book/*.pdf 2>/dev/null | head -n1); \
	if [ -n "$$pdf_file" ]; then cp "$$pdf_file" _book/beyond-code.pdf; fi
	@rm -f _book/$(INTERMEDIATE_STEM).pdf _book/$(INTERMEDIATE_STEM).epub

html:
	@mkdir -p $(CACHE_DIR) $(TEXMF_VAR) $(TEXMF_CONFIG) $(TEXMF_HOME)
	$(RENDER_ENV) $(QUARTO) render --to html

pdf:
	@mkdir -p $(CACHE_DIR) $(TEXMF_VAR) $(TEXMF_CONFIG) $(TEXMF_HOME)
	$(RENDER_ENV) $(QUARTO) render --to pdf
	@pdf_file=$$(ls -1 _book/*.pdf 2>/dev/null | head -n1); \
	if [ -n "$$pdf_file" ]; then cp "$$pdf_file" _book/beyond-code.pdf; fi
	@rm -f _book/$(INTERMEDIATE_STEM).pdf

epub:
	@mkdir -p $(CACHE_DIR) $(TEXMF_VAR) $(TEXMF_CONFIG) $(TEXMF_HOME)
	$(RENDER_ENV) $(QUARTO) render --to epub
	@epub_file=$$(ls -1 _book/*.epub 2>/dev/null | head -n1); \
	if [ -n "$$epub_file" ]; then cp "$$epub_file" _book/beyond-code.epub; fi
	@rm -f _book/$(INTERMEDIATE_STEM).epub

print:
	@mkdir -p $(CACHE_DIR) $(TEXMF_VAR) $(TEXMF_CONFIG) $(TEXMF_HOME)
	$(RENDER_ENV) $(QUARTO) render --profile print --to pdf
	@print_pdf=$$(ls -1 _book/print/*.pdf 2>/dev/null | head -n1); \
	if [ -n "$$print_pdf" ]; then cp "$$print_pdf" _book/print/beyond-code-print.pdf; fi
	@rm -f _book/print/$(INTERMEDIATE_STEM).pdf
	@rm -f _book/$(INTERMEDIATE_STEM).pdf _book/$(INTERMEDIATE_STEM).epub

cover:
	@./scripts/generate_cover.sh

clean:
	rm -rf _book .cache .quarto
