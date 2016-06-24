@[Link(ldflags: "-lclipping -lstdc++")]
lib LibClipping
  struct IntPoint
    x : Int64
    y : Int64
  end

  struct PointBuffer
    points : IntPoint*
    size : Int32
  end

  fun testOffset() : Void
  fun offsetPaths(paths : PointBuffer*, path_count : Int32, delta : Float64, result : PointBuffer **) : Int32
end
