require "./routing"
require "./clipping"
require "./gd"
require "benchmark"

ways = Routing.load_ways
puts "Loaded #{ways.size} ways"

starting_node = 57270
distance = 5300
reachable = Routing.driving_distance starting_node, distance
puts "Ways reachable from node #{starting_node}: #{reachable.size}"


# Filter and convert the ways reachable from the node

PRECISION_FACTOR = 100_000_i64

num_points = 0
paths = ways.select(reachable).map do |way_id, line_string|
  int_points = line_string.points.map do |p|
    x = p.x * PRECISION_FACTOR
    y = p.y * PRECISION_FACTOR
    Clipping::IntPoint.new(x.to_i64, y.to_i64)
  end
  num_points += int_points.size
  Clipping::Path.new(int_points)
end
puts "#{paths.size} ways converted for offsetting totaling #{num_points} points"


# Offset each way and save the buffers
buffers = [] of Clipping::Path
BUFFER_DISTANCE = PRECISION_FACTOR * 0.004
times = Benchmark.measure do
  buffers = paths.map do |path|
    Clipping.offsetPath path, BUFFER_DISTANCE
  end.flatten
end
puts "Offset time: #{times}"

puts "#{buffers.size} buffers generated"
num_points = buffers.map(&.size).sum
puts "#{num_points} total points"

min_x = Int64::MAX
max_x = Int64::MIN
min_y = Int64::MAX
max_y = Int64::MIN

buffers.each do |path|
  minmax_x = path.points.map(&.x).minmax
  minmax_y = path.points.map(&.y).minmax
  min_x = {min_x, minmax_x[0]}.min
  max_x = {max_x, minmax_x[1]}.max
  min_y = {min_y, minmax_y[0]}.min
  max_y = {max_y, minmax_y[1]}.max
end
width = max_x - min_x
height = max_y - min_y

puts "Bounding box: (#{min_x}, #{min_y}) to (#{max_x}, #{max_y})"
puts "Dimensions: #{width} x #{height}"

OUT_WIDTH = 6000
OUT_HEIGHT = 6000

scale_x = OUT_WIDTH.to_f64 / width
scale_y = OUT_HEIGHT.to_f64 / height

scale = {scale_x, scale_y}.min

puts "Scale: #{scale}"

im = GD::Image.new(OUT_WIDTH, OUT_HEIGHT)
im.alpha_blend = false
bg_color = im.allocate_color(0,0,0,127)
im.fill_rectangle 0, 0, OUT_WIDTH-1, OUT_HEIGHT-1, bg_color

fg_color = im.allocate_color(255,0,0,0)
im.alpha_blend = true

png_data = uninitialized GD::DataPtr

times = Benchmark.measure do
  buffers.each do |path|
    poly = Array(GD::Point).new(path.size)
    path.points.each do |point|
      x = (point.x - min_x) * scale
      y = OUT_HEIGHT - (point.y - min_y) * scale
      poly << GD::Point.new(x.to_i32, y.to_i32)
    end
    im.fill_polygon poly, fg_color
  end
  im.save_alpha = true
  png_data = im.render_png
end
puts "Rendering time: #{times}"

File.open("isochrone.png", "wb") do |f|
  f.write png_data.to_slice
end

png_data.destroy
im.destroy
