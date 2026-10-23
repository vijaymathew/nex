# Maps and Navigation in Nex

A digital map of Kestrel Island, an imaginary island in the North Atlantic,
and a navigator for it. Click where you want to start and where you want to
go; watch the route search spread across the roads; then watch a traveller
follow the route while the navigator talks them through every turn.

```
nex navigate.nex                          # the live map, in a window
nex navigate.nex "beach car park" lighthouse
nex route.nex harbour lighthouse          # the same directions, printed
nex checks.nex                            # 55 checks of everything
```

```
$ nex route.nex market harbour
Driving from Market Square to The Harbour: 500 m, about 1 min.
A* explored 7 of the 143 nodes.

  1. Head east on Market Street              100 m
  2. Turn right onto Chapel Street           200 m
  3. Turn right onto Quay Street             200 m
  4. Arrive at The Harbour
```

No mapping or graph library is used. Everything is written out in Nex:
the distance between two latitudes and longitudes, the projection onto a
flat map, the geometry, the drawing (with label placement), breadth-first
search, Dijkstra's algorithm and A*, turn-by-turn directions, map matching
of noisy GPS fixes, and rerouting after a wrong turn. Contracts check the
geometry and the searches as they run.

The tutorial `tutorial/digital_map_tutorial.pdf` builds the whole project
step by step, explaining the ideas and the math along the way.

## The live map

`nex navigate.nex` opens a window showing the island. Click a start and a
destination (or name two places on the command line). The chosen search
algorithm runs, and its explored region unfolds on the map; then the view
zooms in and follows a simulated traveller, with the next instruction
across the top.

| Key | Effect |
|---|---|
| `w` | walk or drive (walkers may use footpaths and ignore one-way streets) |
| `a` | change the search algorithm: A*, breadth-first search, Dijkstra |
| `s` | the shortest way or the quickest |
| `x` | make the traveller take a wrong turn, and watch the navigator reroute |
| `z` | follow the traveller, or show the whole island |
| space | pause |
| `+` / `-` | speed the trip up or slow it down |
| `n` | a new trip |
| `q`, Escape | quit |

Options: `--walk`, `--algorithm bfs|dijkstra|astar`. Without a display, or
with `--snapshot SECONDS FILE` (and optionally `--wrong-turn SECONDS`), it
draws the trip at that moment into a PNG file instead of opening a window.

## Printed directions

```bash
nex route.nex                                 # list the places
nex route.nex harbour lighthouse --walk       # on foot
nex route.nex beach lighthouse --shortest     # shortest rather than quickest
nex route.nex peak lighthouse --algorithm dijkstra
```

A place can be named by any part of its name: `mill` is Old Mill.

## Files

| File | What it does |
|---|---|
| `kestrel_island.map` | The map: nodes with latitude and longitude, and roads, areas, a river and places built from them (a small cousin of OpenStreetMap's format) |
| `helpers.nex` | Printing numbers, distances and times neatly, and finding the user's files |
| `geo.nex` | `Lat_Lon` with the haversine distance, flat `Point`s, and the local `Projection` between them |
| `geometry.nex` | Line lengths, closest point on a segment, polygon area and point-in-polygon, bearings and turn angles |
| `island_map.nex` | `Island_Map`: reads the map file; roads, areas, rivers and places |
| `map_view.nex` | Draws the map at any zoom: layers, road casings, greedy label placement, scale bar, north arrow |
| `road_graph.nex` | The roads as a graph for driving or walking, with lengths, travel times and one-way streets |
| `search.nex` | Breadth-first search, Dijkstra's algorithm and A* |
| `directions.nex` | Turns a route into instructions |
| `route.nex` | Prints directions between two places |
| `navigation.nex` | Follows a traveller along a route from GPS fixes, says what to do next, and reroutes |
| `simulation.nex` | A simulated traveller (who can take wrong turns) and a noisy GPS |
| `navigate.nex` | The live map |
| `checks.nex` | Executable checks of all of the above |

## The tutorial

The tutorial's LaTeX source, `tutorial/tutorial.tex` and the chapters it
inputs, is also the source of the code: every line of every `.nex` file
appears in it, in the order a reader adds it.

```bash
cd tutorial
python3 build.py            # rebuild the code from the tutorial, run every
                            # program, compare with ../*.nex, typeset the PDF
python3 build.py --no-pdf   # everything except typesetting
python3 build.py --pdf-only # only typeset, using the last build's outputs
python3 build.py --sync     # also copy the tutorial's code into the project
```

Building the PDF needs `pdflatex` (TeX Live). Every output and every map
picture in it comes from running the code as printed.
