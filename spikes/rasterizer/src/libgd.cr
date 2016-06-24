@[Link("gd")]
lib LibGD
  struct Point
    x : Int32
    y : Int32
  end

  alias Image = Void

  fun image_create = gdImageCreate(sx : Int32, sy : Int32) : Image*
  fun image_create_true_color = gdImageCreateTrueColor(sx : Int32, sy : Int32) : Image*
  fun image_destroy = gdImageDestroy(im : Image*) : Void

  fun image_color_allocate = gdImageColorAllocate(im : Image*, r : Int32, g : Int32, b : Int32) : Int32
  fun image_color_allocate_alpha = gdImageColorAllocateAlpha(im : Image*, r : Int32, g : Int32, b : Int32, a : Int32) : Int32
  fun image_save_alpha = gdImageSaveAlpha(im : Image*, save_alpha : Int32) : Void
  fun image_alpha_blending = gdImageAlphaBlending(im : Image*, alpha_blending : Int32) : Void

  fun image_line = gdImageLine(im : Image*, x1 : Int32, y1 : Int32, x2 : Int32, y2 : Int32, color : Int32) : Void
  fun image_polygon = gdImagePolygon(im : Image*, points : Point*, num_points : Int32, color : Int32) : Void
  fun image_filled_polygon = gdImageFilledPolygon(im : Image*, points : Point*, num_points : Int32, color : Int32) : Void
  fun image_open_polygon = gdImageOpenPolygon(im : Image*, points : Point*, num_points : Int32, color : Int32) : Void
  fun image_filled_rectangle = gdImageFilledRectangle(im : Image*, x1 : Int32, y1 : Int32, x2 : Int32, y2 : Int32, color : Int32) : Void

  fun image_png_ptr = gdImagePngPtr(im : Image*, size : Int32*) : Void*
  fun free = gdFree(ptr : Void*) : Void
end
