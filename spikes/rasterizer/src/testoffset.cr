require "pg"
require "./clipping"
require "benchmark"

struct Point
  property lat, lon

  def initialize(@lat : Float64, @lon : Float64)
  end
end

struct LineString
  property points : Array(Point)
  property open : Bool

  def initialize(@points : Array(Point))
    @open = true
  end

  def initialize(path : Tuple)
    @open = path[0] == :open
    @points = (path[1].as(Array)).map { |p| Point.new(p[1], p[0]) }
  end
end

# Load ways from PostGIS

num_ways = if ARGV.size > 0
             ARGV[0]
           else
             "1000"
           end

PG::Decoders.register_geo

DB = PG.connect("postgres://localhost/routing")

lines = {} of Int64 => LineString

query = <<-QRY
  SELECT gid, the_geom::path FROM ways
  WHERE gid IN
  (SELECT edge FROM pgr_drivingDistance('SELECT gid AS id, source, target, cost_s AS cost FROM ways', 1470, 5400, false))
QRY

query = <<-QRY
  SELECT gid, the_geom::path FROM ways
  ORDER BY gid
  LIMIT #{num_ways}
QRY

DB.exec({Int64, Tuple}, query) do |(way_id, the_geom)|
  lines[way_id] = LineString.new(the_geom)
end

num_points = lines.map do |way_id, line|
  line.points.size
end.sum
puts "Loaded #{lines.size} ways totaling #{num_points} points"

# Convert to integers for fixed precision arithmetic

PRECISION_FACTOR = 100_000_i64

paths = lines.map do |way_id, line_string|
  int_points = line_string.points.map do |p|
    x = p.lon * PRECISION_FACTOR
    y = p.lat * PRECISION_FACTOR
    Clipping::IntPoint.new(x.to_i64, y.to_i64)
  end
  Clipping::Path.new(int_points)
end

times = Benchmark.measure do
  Clipping.offsetPaths paths, 2000
end

puts "Finished in #{times.real} seconds (#{lines.size/times.real} ways/sec, #{num_points/times.real} points/sec)"
