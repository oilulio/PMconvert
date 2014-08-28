PMconvert
=========

A Java converter from the Documagix PaperMaster format to a normal filestructure.
The PaperMaster program no longer seems to be available and legacy data can be 
locked in, especially as PaperMaster does not seem to run on more recent
Windows versions.

Has worked for my filestore.  Now available for others to use and/or upgrade

Methods inferred from reverse-engineering the file structure, not 
absolutely guaranteed, but tested on a large database.


Algorithm method.  First pass detects all directories under the defined
root point and extracts two global lists (filenames,drawers) which 
defines the mapping between the hex-filenames and the human readable 
filenames.
Second pass finds all images and moves them to a new directory with 
the human readable names.  Also looks at Native files and converts 
these, including .HTM which have images in a subdirectory "I" with
relative links. Any JPG and TIFF image found triggers the movement 
(if it exists) of the same-name OCR .txt file 
