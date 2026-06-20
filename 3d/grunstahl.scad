/*
 * Grünstahl Case
 * Copyright (c) 2025 Lone Dynamics Corporation. All rights reserved.
 *
 * required hardware:
 *  - 1 x M2.5 x 10mm countersunk bolt
 *  - 1 x M2.5 nut
 *
 */

$fn = 30;

box_width = 19;
box_length = 26;
box_height = 10.5;
box_thickness = 2.0;

lid_thickness = 3;

cutout_width = 16.10;
cutout_length = 42.5;
cutout_height = 4.5;

cutout_usb_width = 8.4;

board_height = 0.8;

roundness = 1;

toladj = 0.25;

//translate([0,-5.25,0]) ssg_board(-0.25);
//translate([0,0,-0.5]) ssg_case();
translate([0,4.5,0]) ssg_endcap(0);

// m2.5 bolt
//translate([0,-11.5,-6.25]) color([1,0,0]) cylinder(d=2.5, h=12, $fn=36);

// m2.5 nut
//translate([0,-13.5,-6.55]) rotate(30) color([1,0,0]) cylinder(d=5.8, h=2.4, $fn=6);

module ssg_board(o)
{
	
	difference() {
		union() {
			translate([0,7,o])
				color([0.0,0.8,0.0]) cube([16, 16, 1.6], center=true);
			translate([0,20,o])
				color([0.9,0.9,0.9]) cube([8.2, 10, 2.4], center=true);
		}
		translate([0,-11,-10]) cylinder(d=4.2, h=100);
	}
	
}

module ssg_endcap(o)
{
		 	
	difference() {
        translate([0,-14.5,0]) cube([15.8,6,4.25], center=true);
		translate([0,-13.5,-4.25]) color([1,0,0]) cylinder(d=3.25, h=8, $fn=36);

	}
}

module ssg_case()
{
	
	difference() {
		
		minkowski() {
			cube([box_width-(roundness*2),box_length-(roundness*2),box_height-(roundness*2)],
				center=true);
			sphere(roundness);
		}
	
		translate([0,-10.5,(box_height-cutout_height)/2-2.5])
			cube([cutout_width+toladj,cutout_length,cutout_height], center=true);

		// USB cutout
		translate([0,10,0.25])
			cube([cutout_usb_width+toladj,10,2.4+toladj], center=true);

		// bolt hole
		translate([0,-9,-5]) cylinder(d=2.75+toladj, h=100, $fn=36);
		
		// nut cutout
		translate([0,-9,-5.75]) rotate(0) cylinder(d=6+toladj, h=2.5, $fn=6);
		
		// lid notch cutout
		translate([0,-9,4.25]) cylinder(d=5+toladj, h=100);

	}

}