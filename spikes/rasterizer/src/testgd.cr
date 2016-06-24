require "./gd"

im = GD::Image.new(64, 64)
im.alpha_blend = false
black = im.allocate_color(0, 0, 0, 127)
im.fill_rectangle 0, 0, 63, 63, black

white = im.allocate_color(255, 0, 0, 0)

im.alpha_blend = true
im.draw_line 0, 0, 63, 63, white

poly = [] of GD::Point
poly << GD::Point.new(32,0)
poly << GD::Point.new(63,31)
poly << GD::Point.new(31,63)
poly << GD::Point.new(0,32)
im.alpha_blend = true
im.fill_polygon poly, white

im.alpha_blend = false
im.save_alpha = true
png_data = im.render_png

File.open("test.png", "wb") do |f|
  f.write png_data.to_slice
end

png_data.destroy
im.destroy
