# Optimistic Locking & Idempotence — Presentation

Slides generated from `optimistic-locking-idempotence.md` using Pandoc + Mermaid inside Docker.

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/)

## Build the image (one-time)

```bash
docker build -t pandoc-mermaid .
```

> **Apple Silicon (M1/M2/M3):** add `--platform linux/amd64` to both `docker build` and `docker run` if you encounter
> architecture errors.

## Generate slides

Run from the directory containing your Markdown file — the current directory is mounted into the container at `/data`.

### PowerPoint (`deck.pptx`)

```bash
docker run --rm -v "$(pwd):/data" pandoc-mermaid \
  bash -c 'MERMAID_FILTER_FORMAT=png pandoc optimistic-locking-idempotence.md -o deck.pptx --slide-level=2 -F mermaid-filter'
```

### Beamer PDF (`deck.pdf`)

```bash
docker run --rm -v "$(pwd):/data" pandoc-mermaid \
  bash -c 'MERMAID_FILTER_FORMAT=png pandoc optimistic-locking-idempotence.md -o deck.pdf -t beamer --slide-level=2 --pdf-engine=xelatex -V mainfont="Noto Sans" -V monofont="Noto Sans Mono" -H /beamer-unicode.tex -F mermaid-filter'
```

## What's in the image

| Tool                                         | Purpose                                             |
|----------------------------------------------|-----------------------------------------------------|
| Pandoc                                       | Markdown → PPTX / Beamer PDF conversion             |
| mermaid-filter                               | Renders Mermaid diagrams as PNG during conversion   |
| Chromium                                     | Headless browser used by mermaid-filter             |
| XeLaTeX                                      | PDF engine with full Unicode support                |
| Noto Sans / Noto Sans Symbols2 / DejaVu Sans | Fonts covering all Unicode characters in the slides |

## Notes

- `--slide-level=2` means `##` headings each become a new slide
- `beamer-unicode.tex` (bundled in the image at `/beamer-unicode.tex`) maps Unicode symbols like →, ≠, ✔, ✘ to fonts
  that support them
- The build step is one-time and takes a few minutes
