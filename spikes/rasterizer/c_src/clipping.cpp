#include "clipper.hpp"
#include<iostream>

using namespace ClipperLib;

struct Point {
  cInt x;
  cInt y;
};

struct PointBuffer {
  Point* points;
  int size;
};

extern "C" {
  void testOffset();
  int offsetPaths(PointBuffer*, int, double, PointBuffer**);
}

void testOffset()
{
  Path subj;
  Paths solution;
  subj <<
    IntPoint(348,257) << IntPoint(364,148) << IntPoint(362,148) <<
    IntPoint(326,241) << IntPoint(295,219) << IntPoint(258,88) <<
    IntPoint(440,129) << IntPoint(370,196) << IntPoint(372,275);
  ClipperOffset co;
  co.AddPath(subj, jtRound, etClosedPolygon);
  co.Execute(solution, -7.0);

  std::cout << solution << std::endl;
}

Path pointBufferToPath(PointBuffer *point_buffer)
{
  Path result;
  for (int j = 0; j < point_buffer->size; j++) {
    Point *p = &point_buffer->points[j];
    result << IntPoint(p->x, p->y);
  }
  return result;
}

void pathToPointBuffer(const Path& path, PointBuffer *point_buffer)
{
  point_buffer->size = path.size();
  point_buffer->points = (Point *) malloc(sizeof(Point) * path.size());
  for (int i = 0; i < path.size(); i++) {
    point_buffer->points[i].x = path[i].X;
    point_buffer->points[i].y = path[i].Y;
  }
}


int offsetPaths(PointBuffer* paths, int path_count, double delta, PointBuffer **result)
{
  ClipperOffset co;
  for (int i = 0; i < path_count; i++) {
    PointBuffer *path = &paths[i];
    Path subj = pointBufferToPath(path);
    co.AddPath(subj, jtRound, etOpenRound);
  }
  Paths solution;
  co.Execute(solution, delta);

  if (result) {
    *result = (PointBuffer *)malloc(sizeof(PointBuffer) * solution.size());
    for (int i = 0; i < solution.size(); i++) {
      pathToPointBuffer(solution[i], *result + i);
    }
  }
  return solution.size();
}
