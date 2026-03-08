.PHONY: html clean

CACHE_DIR := $(CURDIR)/.cache
RENDER_ENV := XDG_CACHE_HOME=$(CACHE_DIR)

html:
	@mkdir -p $(CACHE_DIR)
	$(RENDER_ENV) quarto render

clean:
	rm -rf _site .quarto .cache
