require "./libclipping"

module Clipping
  struct LibClipping::IntPoint
    def initialize(@x : Int64, @y : Int64)
    end

    def x
      @x
    end

    def y
      @y
    end
  end

  alias IntPoint = LibClipping::IntPoint

  struct Path
    property points : Array(IntPoint)

    def initialize(@points : Array(IntPoint))
    end

    def initialize(point_buffer : Pointer(IntPoint), count)
      @points = Array.new(count) do |idx|
        IntPoint.new(point_buffer[idx].x, point_buffer[idx].y)
      end
    end

    def size
      points.size
    end

    def as_point_buffer
      pb = uninitialized LibClipping::PointBuffer
      pb.points = points.to_unsafe
      pb.size = points.size
      pb
    end
  end

  def self.offsetPaths(paths, delta)
    array_of_paths = paths.map do |path|
      path.as_point_buffer
    end
    result_count = LibClipping.offsetPaths(array_of_paths.to_unsafe, array_of_paths.size, delta, out result)
    result = Slice(LibClipping::PointBuffer).new(result, result_count)
    solution = (0..result_count - 1).map do |idx|
      path = Path.new(result[idx].points, result[idx].size)
      LibC.free result[idx].points as Void*
      path
    end
    LibC.free result.to_unsafe as Void*
    solution
  end

  def self.offsetPath(path, delta)
    offsetPaths([path], delta)
  end
end
