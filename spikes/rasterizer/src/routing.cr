require "pg"

module Routing
  @@db : PG::Connection = PG.connect("postgres://localhost/routing")

  def self.load_ways
    ways = {} of Int64 => PG::Geo::Path

    query = <<-QRY
      SELECT gid, the_geom::path FROM ways
    QRY

    @@db.exec({Int64, PG::Geo::Path}, query) do |(way_id, the_geom)|
      ways[way_id] = the_geom
    end

    ways
  end

  def self.driving_distance(start, distance)
    query = <<-QRY
      SELECT edge
      FROM pgr_drivingDistance('SELECT gid AS id, source, target, cost_s AS cost ' ||
                               'FROM ways', #{start}, #{distance}, false)
      WHERE edge > 0
    QRY

    edges = [] of Int64

    @@db.exec({Int64}, query) do |(edge)|
      edges << edge
    end

    edges
  end
end
