OBJECTS = \
	clipper.o \
	clipping.o

HEADERS = clipper.hpp

CXXFLAGS = -Wall -O2 -g
LDFLAGS =
LIBTOOL = libtool

all: libclipping.a

libclipping.a: $(OBJECTS)
	$(LIBTOOL) -static -o $@ $+

%.o: %.cpp $(HEADERS)
	$(CXX) $(CXXFLAGS) -o $@ -c $<

clean:
	- rm -f $(OBJECTS) libclipping.a

