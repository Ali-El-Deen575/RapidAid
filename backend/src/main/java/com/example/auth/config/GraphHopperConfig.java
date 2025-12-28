package com.example.auth.config;

import com.graphhopper.GraphHopper;
import com.graphhopper.config.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

@Configuration
public class GraphHopperConfig {

    @Value("${graphhopper.osm.file:src/main/resources/egypt-251227.osm.pbf}")
    private String osmFile;

    @Value("${graphhopper.graph.location:target/routing-graph-cache}")
    private String graphLocation;

    @Bean
    public GraphHopper graphHopper() {
        GraphHopper hopper = new GraphHopper();
        
        File osmFileCheck = new File(osmFile);
        if (!osmFileCheck.exists()) {
            throw new RuntimeException("OSM file not found: " + osmFile);
        }
        
        hopper.setOSMFile(osmFile);
        hopper.setGraphHopperLocation(graphLocation);
        
        hopper.setProfiles(
            new Profile("car").setVehicle("car").setWeighting("fastest"),
            new Profile("emergency").setVehicle("car").setWeighting("shortest")
        );
        
        hopper.importOrLoad();
        return hopper;
    }
}