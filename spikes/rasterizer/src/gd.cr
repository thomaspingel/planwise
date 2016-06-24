require "./libgd"

struct LibGD::Point
  def initialize(@x, @y)
  end
end

module GD
  alias Point = LibGD::Point

  class Image
    def initialize(@im : LibGD::Image*)
    end

    def initialize(sx, sy, type = :true_color)
      @im = case type
            when :true_color
              LibGD.image_create_true_color(sx, sy)
            when :palleted
              LibGD.image_create(sx, sy)
            else
              raise "invalid image type #{type}"
            end
    end

    def destroy
      LibGD.image_destroy @im
      @im = nil
    end

    def to_unsafe
      @im.not_nil!
    end

    def allocate_color(r, g, b)
      LibGD.image_color_allocate @im, r, g, b
    end

    def allocate_color(r, g, b, a)
      LibGD.image_color_allocate_alpha @im, r, g, b, a
    end

    def save_alpha=(save)
      LibGD.image_save_alpha @im, save ? 1 : 0
    end

    def alpha_blend=(blending)
      LibGD.image_alpha_blending @im, blending ? 1 : 0
    end

    def draw_line(x1, y1, x2, y2, color)
      LibGD.image_line @im, x1, y1, x2, y2, color
    end

    def draw_path(points, color)
      LibGD.image_open_polygon @im, points, points.size, color
    end

    def draw_polygon(points, color)
      LibGD.image_polygon @im, points, points.size, color
    end

    def fill_polygon(points, color)
      LibGD.image_filled_polygon @im, points, points.size, color
    end

    def fill_rectangle(x1, y1, x2, y2, color)
      LibGD.image_filled_rectangle @im, x1, y1, x2, y2, color
    end

    def render_png
      data = LibGD.image_png_ptr(@im, out size)
      DataPtr.new data, size
    end
  end

  class DataPtr
    def initialize(@data : Void*, @size : Int32)
    end

    def to_slice
      Slice.new @data as UInt8*, @size
    end

    def destroy
      LibGD.free @data
    end
  end
end
