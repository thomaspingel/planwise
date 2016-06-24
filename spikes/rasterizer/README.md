# Rasterizer

Experiment implementing a rasterizer for the isochrones using GD and the
Clipper clipping library, implemented in Crystal.


## Usage

You need to have the database setup as explained in `/doc/Database-Setup.md`.
This experiment requires Crystal 0.18.x.

1. Compile the Clipper library:

```
$ (cd c_src; make)
```

2. Setup the library path so the compiler can find the Clipper library

```
$ export LIBRARY_PATH=`pwd`/c_src:$LIBRARY_PATH
```

3. Run the test program

```
$ crystal src/testrouting.cr
```

That will generate a file `isochrone.png` with the rendered isochrone. Starting
node and distance are currently hard-coded.

