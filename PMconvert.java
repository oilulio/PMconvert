import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Arrays;
import java.util.ArrayList;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.regex.Pattern;
import static java.nio.file.StandardCopyOption.*;
import java.nio.file.Paths;
import java.util.Date;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.Scanner;

class PMconvert {

// Class to convert a DocuMagix Papermaster 98 (and perhaps other) 
// filesystem to a human readable normal file structure.

/* Copyright (C) 2014  S Combes

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// Methods inferred from reverse-engineering the file structure, not 
// absolutely guaranteed, but tested on a large database.

// Structure seems to be mostly 8-digit ASCII hex directory and filenames 
// (e.g. 000001F2) with associated metadata (in "_PFC._PS" files at each 
// level) giving the human-intended free text name of each hex item. 
// Hex items appear to be globally unique and build from 00000001.

// So, /00000002/00000003/00000004 can be mapped (by _PFC._PS files at 
// each level) into, say, "/Personal Documents/Taxes/Page 1".  The lowest
// level is typically page numbered (by a slightly different encoding).

// The files at the lowest level are typically .tiff or .jpg, without 
// the extension and with an extra header (see method makeImage() below).
// They can also be 'native' files, e.g. pdfs, with a different encoding.
// In that case their filename is "Native" followed by the original
// extension, e.g. .jpg.  

// Other files seem to be :

// .THM : believed to be thumbnails and ignored by the conversion as 
// redundant data.

// ATTRIB.INE : present in a directory with JPGs.  Defines the original 
// scan resolution.  

// xxxxxxxx.TXT in same directory as an image xxxxxxxx, which is the 
// (spatially matching) OCR text of the image

// xxxxxxxx.TSI in directory above an image, being a dictionary of the 
// words in the OCR of the image. Not converted, as easier to reconstruct
// (from .txt files) in a format of own choosing if needed

// Various files in the top level directory (CDATA.DAT, CABINFO.DAT, 
// CABLOCK.LKE) which may contain structural data but seem to be 
// redundant compared to that extracted from the directories themselves.

// Algorithm method.  First pass detects all directories under the defined
// root point and extracts two global lists (filenames,drawers) which 
// defines the mapping between the hex-filenames and the human readable 
// filenames.
// Second pass finds all images and moves them to a new directory with 
// the human readable names.  Also looks at Native files and converts 
// these, including .HTM which have images in a subdirectory "I" with
// relative links. Any JPG and TIFF image found triggers the movement 
// (if it exists) of the same-name OCR .txt file 

String rootPath;
String reRootPath;
PrintWriter pwl;

boolean [] used;
Map<String,String> map;
// The human user free text name of the drawer e.g. "My taxes" keyed by the 
// associated legacy filename, an 8 digit ASCII hex number, e.g. 000001A7, 
// represented as a String.

PMconvert(String rootPath,String reRootPath) 
{
this.rootPath=rootPath;
this.reRootPath=reRootPath;
int imagesConverted=0;

map=new HashMap<String,String>();

System.out.println("Converter for PaperMaster 98 file format");
System.out.println();

new File(reRootPath).mkdirs();

try {
  pwl=new PrintWriter(new File(reRootPath+File.separator+"log.txt")); 
  pwl.println("------------- CONVERSION FROM PAPERMASTER FILESYSTEM LOG ---------------");
  pwl.println();
  pwl.println("Started at "+new Date());
  pwl.println();

System.out.println("Scanning directories for naming structure information");

File poss=new File(rootPath+File.separator+"_PFC._PS");

if (poss.exists())  // "_PRF._PS" file in root directory
   buildMap(poss);  // So map it

List<Path> directoriesToDo=PMconvert.getDirectories(new ArrayList<Path>(),
                                 FileSystems.getDefault().getPath(rootPath));
for (Path p : directoriesToDo) {  
  System.out.print(". ");
  poss=new File(p.toAbsolutePath().toString()+File.separator+"_PFC._PS");

  if (poss.exists())   // "_PRF._PS" file in this directory
      buildMap(poss);  // So map it
}

System.out.println();
System.out.println("Processing files");

List<Path> filesToDo=PMconvert.getFileNames(new ArrayList<Path>(),
                                FileSystems.getDefault().getPath(rootPath));
for (Path p : filesToDo) {
  System.out.print(". ");

  String parentDir= p.getParent().toString();

  int dot=p.getFileName().toString().indexOf(".");
  if (parentDir.toUpperCase().substring(parentDir.length()-5).equals("ICONS")) {
  // SKIP THIS
  }
  else if (parentDir.toUpperCase().substring(parentDir.length()-7).equals("AF_DATA")) {
  // SKIP THIS
  }
  else if (dot==(-1)) {
    if (!parentDir.substring(parentDir.length()-2).equals("\\I")) {
      makeImage(parentDir,p.getFileName().toString());
      imagesConverted++;
    }
  }
  else if (p.getFileName().toString().substring(0,6).toUpperCase().equals("NATIVE")) {

    pwl.println("Found native "+p.getFileName().toString()+"  "+p.toAbsolutePath().toString());

    String extension=p.getFileName().toString().substring(p.getFileName().toString().indexOf(".")); // Includes dot

    String newName=humanFilename(p.getParent().toString()); 
    boolean made=new File(newName).mkdirs();

    pwl.println("Produced native "+map.get(p.getParent().getFileName().toString())+extension);

    try {
      Files.copy(p, Paths.get((newName+File.separator+map.get(p.getParent().getFileName().toString())+extension)),
                 REPLACE_EXISTING);
    } catch (IOException e) {  
       System.out.println("Error copying file");
       pwl.println("Error copying file");
    }
  }
  else if (parentDir.toUpperCase().substring(parentDir.length()-2).equals("\\I")) {

    String newName=humanFilename(p.getParent().getParent().toString())+"\\I"; 
    // The "I" won't be in the mapping.  Must be extracted and replaced.
    boolean made=new File(newName).mkdirs();

    pwl.println("Copying HTM embedded image "+p.getFileName().toString()+" into "+newName);

    try {
      Files.copy(p, Paths.get(newName+File.separator+p.getFileName().toString()), REPLACE_EXISTING);
    } catch (IOException e) {
       System.out.println("Error copying file");
       pwl.println("Error copying file");
    }
  }
  else if (p.getFileName().toString().substring(dot+1).toUpperCase().equals("THM")) {  
    // For future use, if required.  Ignored for now.
    // Believe these are thumbnails, i.e. redundant data 
  }
}
System.out.println();
System.out.println("Images Converted   = "+imagesConverted);

pwl.close(); 
} catch (IOException e) { System.out.println("Error on log file"); }

System.out.println();
System.out.println("All done.  Inspect log.txt in "+reRootPath+" for summary");

}
// ------------------------------------------------------------------------------
void copyTextFile(Path from,Path to)
{
File fileFrom=new File(from.toString());
if (!fileFrom.exists()) return;
 
pwl.println("Copying associated OCR text file");
try {
  Files.copy(from,to,REPLACE_EXISTING);
} catch (IOException e) {
  System.out.println("Error copying file "+fileFrom.getAbsolutePath());
  pwl.println("Error copying file");
}
}
// ------------------------------------------------------------------------------
void buildMap(File psFile) {

pwl.println("Getting name information in directory "+psFile.getAbsolutePath());

try {
  boolean skipping=true;
  StringBuffer sb=new StringBuffer(1000);

  DataInputStream dis=new DataInputStream(new BufferedInputStream(new FileInputStream(psFile)));

  while (true) {  // Seek "NAME" = 0x4E414D45 at 32 byte alignnment
    int name=dis.readInt();
    if (name==0x4E414D45) {
      break;
    }
    dis.skipBytes(28);  // Keep to 32 alignment
  }

A:  while (dis.available()>0) {
    sb.append((char)dis.read());
    boolean valid=false;
    boolean validPage=false; // Testing for different file structure at the page level
    String candidate="";
    if (sb.length()>=8) {  // Seek an 8-byte ASCII hex number
      candidate=sb.substring(sb.length()-8); // Last 8 chars
      valid=valid8hex(candidate);
 
      if (!valid && sb.length()>=28) {  // Seek 24 x 0x00 then 0x02,0x00,0x18,0x00 
        candidate=sb.substring(sb.length()-28); // Last 28 chars
        validPage=true;
        for (int i=0;i<24;i++) // Starts with 24 x 0x00
          if (candidate.charAt(i)!=0x00) validPage=false;
        if (candidate.charAt(24) !=0x02) validPage=false;
        if (candidate.charAt(25) !=0x00) validPage=false;
        if (candidate.charAt(26) !=0x18) validPage=false;
        if (candidate.charAt(27) !=0x00) validPage=false;
      }
    }
    if (!valid && !validPage) continue A;

    if (validPage) { 
      while (dis.available()>0) {

        int type=dis.readByte();
        if (type==0x20) { // Page numbering
          dis.skipBytes(1); 
          int index=(0xFF & dis.readByte())/32 + (0xFF & dis.readByte())*8 -5;
          // Curious encoding. Starts at A0,00 (little endian) Increments in 0x20s
          // (i.e. becomes C0,00) then wraps to (00,01), (02,01) ... etc
          // Purely for reference.  The 5 ascii encoded decimal digits (below) 
          // are the actual page order
          dis.skipBytes(4); 
          StringBuffer hexName=new StringBuffer(10); 
          // Next 8 are the ASCII encoded hex filename
          for (int i=0;i<8;i++)
            hexName.append((char)dis.read());
          dis.skipBytes(10); 
          StringBuffer pageOrder=new StringBuffer(6); 
          // Next 5 are the ASCII encoded decimal page order
          for (int i=0;i<5;i++)
            pageOrder.append((char)dis.read());
          dis.skipBytes(1); 

          int page=Integer.parseInt(pageOrder.toString());  // Numbered from 0
          map.put(hexName.toString(),"Page"+String.format("%05d",(page+1)));
          pwl.println(hexName.toString()+" is page "+String.format("%05d",(page+1)));
        }
        else if (type==0x25) { // Annotation - ignore [Not well tested, only one sample]
          dis.skipBytes(36); 
        } else {
          System.out.println("Unknown type - stopping");
          System.exit(0);  
        }
      }
    } else {

      int textLen=0;
      StringBuffer sb2=new StringBuffer(100);
      byte c;
      B : while (true) {
        c=dis.readByte();
        sb.append((char)c);
        if (c==0x00) { // See if the run up to this matches the pattern
          int len=sb2.length();
          textLen=0;
          int i=len-1;
          while (i>=0 && sb2.charAt(i)!=0x00) { i--; textLen++; }
          if (i>=3) {  // Allows room for preamble
            int statedLen=sb2.charAt(i-1);
            // Curious test, based on not fully understanding encoding.  Looks for
            // Pattern 00 00 xx 00 ..... 00 where the .... are a run of non-zero
            // bytes that form the Drawer name.  Their length is predicted by the
            // xx byte -2 or -3.  High probability this only matches when correct.
            // Consequence of missing a match is to promote by 1 level in filesystem,
            // so no lost data.  A false match would insert a drawer, but again no lost data.

            if (sb2.charAt(i-2)==0x00 && sb2.charAt(i-3)==0x00 && textLen!=0 &&
               ((textLen+3)==statedLen || (textLen+2)==statedLen)) break B;
          }
        }
        sb2.append((char)c);
      } 
      map.put(candidate,sanitisedFilename(sb2.toString().substring(sb2.length()-textLen)));
      pwl.println(candidate+" is "+sanitisedFilename(sb2.toString().substring(sb2.length()-textLen)));
    }
  }
  dis.close();

} catch (IOException e) { e.printStackTrace(); System.out.println("File Error"+psFile.getAbsolutePath()); } 

}
// ------------------------------------------------------------------------------
static String sanitisedFilename(String fn) 
{ // These are the Windows ones, a superset of the Linux ones.  Strictly '.' is
  // allowed, but caused trouble as well when consecutive.
return fn.replace(">","_").replace("<","_").replace(":","_").replace("/","_").replace("\\","_").
    replace("|","_").replace("?","_").replace("\"","_").replace("*","_").replace(".","_");
}
// ------------------------------------------------------------------------------
static boolean valid8hex(String test) 
{ // Makes sure a given string can be read as hex
for (int i=0;i<8;i++)
  if ("0123456789ABCDEF".indexOf(test.charAt(i))==(-1)) 
    return false;
return true;
}
// ------------------------------------------------------------------------------
String humanFilename(String mydir) 
{
String sub=mydir.substring(mydir.indexOf(rootPath)+1+rootPath.length());
String [] elems=sub.split(Pattern.quote(File.separator));
StringBuffer newName=new StringBuffer(150);
newName.append(reRootPath);
for (String e : elems) {
  if (!map.get(e).equals("___system_drawer_1___")) // Skip this special case
    // has effect of promoting Inbox by 1 level in filestructure.
    newName.append(File.separator+((map.get(e)==null)?"Default":map.get(e)));
}
return newName.toString();
}
// ------------------------------------------------------------------------------
void makeImage(String mydir,String myfile)
{
// Certain files in the lowest subdirectories seem to be .tiff or .jpg files with
// some random prefix, for tiff this is usually "DMFILE    " prior to the "II*" 
// that actually starts a tiff.  The files seem to have no extension, unlike
// any other file (although directories share the pattern).
// They are usually 8 character file names, of hex digits, e.g. "000003C6"
// For .jpg files.  On these, strip all before the 0xFF 0xD8 marker

boolean plausible=(myfile.length()==8) && valid8hex(myfile);

if (!plausible) {
  System.out.println("Warning : "+myfile+" in directory "+mydir+" does not fit expected naming convention");
  pwl.println("Warning : "+myfile+" in directory "+mydir+" does not fit expected naming convention");
}
String newName=humanFilename(mydir);
boolean made=new File(newName).mkdirs();

File file=new File(mydir+File.separator+myfile);

pwl.println("Converting image from "+myfile+" in "+mydir);

try {
  boolean skipping=true;
  StringBuffer sb=new StringBuffer(100);

  BufferedInputStream bis=new BufferedInputStream(new FileInputStream(file));
  BufferedOutputStream bos=new BufferedOutputStream(new FileOutputStream(new File("dummy")));

  String jpg=Character.toString((char)0xFF)+Character.toString((char)0xD8);

  while (skipping && bis.available()>0) {
    sb.append((char)bis.read());
    if (sb.indexOf("II*")!=(-1)) {  // That's a TIFF
      bos=new BufferedOutputStream(new FileOutputStream(new File(newName.toString()+File.separator+map.get(myfile)+".tiff")));
      pwl.println("producing "+map.get(myfile)+".tiff in "+newName.toString());
      bos.write('I');
      bos.write('I');
      bos.write('*');
      skipping=false;
      new File(newName.toString()+File.separator+"OCR").mkdirs();
      copyTextFile(Paths.get(mydir+File.separator+myfile+".txt"),
                   Paths.get(newName.toString()+File.separator+"OCR"+File.separator+map.get(myfile)+".txt"));
    }
    else if (sb.indexOf(jpg)!=(-1)) {  // That's a JPG
      bos=new BufferedOutputStream(new FileOutputStream(new File(newName.toString()+File.separator+map.get(myfile)+".jpg")));
      pwl.println("producing "+map.get(myfile)+".pdf in "+newName.toString());
      bos.write((char)0xFF);
      bos.write((char)0xD8);
      skipping=false;
      copyTextFile(Paths.get(mydir+File.separator+myfile+".txt"),
                   Paths.get(newName.toString()+File.separator+map.get(myfile)+".txt"));
    }
  }

  while (bis.available()>0)           // Just copy the rest
    bos.write((byte)bis.read());
 
  bos.flush();
  bis.close();
  bos.close();
} catch (IOException e) { 
  System.out.println("File Error "+newName.toString()+File.separator+map.get(myfile)+
            "  "+myfile+" in "+mydir); 
  pwl.println("File Error "+newName.toString()+File.separator+map.get(myfile)+
            "  "+myfile+" in "+mydir); 
} 
return;
}
// ------------------------------------------------------------------------------
// Adapted From http://stackoverflow.com/questions/2534632/list-all-files-from-a-directory-recursively-with-java
private static List<Path> getFileNames(List<Path> fileNames, Path dir)
{
try {
  DirectoryStream<Path> stream = Files.newDirectoryStream(dir);
  for (Path path : stream) {
    if (path.toFile().isDirectory()) getFileNames(fileNames, path);
    else                             fileNames.add(path);
  }
  stream.close();
} catch(IOException e) { e.printStackTrace(); }
return fileNames;
} 
// ------------------------------------------------------------------------------
// Adapted From http://stackoverflow.com/questions/2534632/list-all-files-from-a-directory-recursively-with-java
private static List<Path> getDirectories(List<Path> dirNames, Path dir)
{
try {
  DirectoryStream<Path> stream = Files.newDirectoryStream(dir);
  for (Path path : stream) {
    if (path.toFile().isDirectory()) {
      dirNames.add(path);
      getDirectories(dirNames, path);
    }
  }
  stream.close();
} catch(IOException e) { e.printStackTrace(); }
return dirNames;
} 
// ------------------------------------------------------------------------------
public static void main(String [] args) {

System.out.println("PM Convert : method of converting old PaperMaster file cabinets");
System.out.println("to normal filesystem records.");
System.out.println();
System.out.println("Provided in good faith.  No warranty expressed or implied");
System.out.println();

if (args.length!=2) {
  System.out.println("Usage java PMconvert sourceDirectory destinationDirectory");
  System.out.println("Source should be the 'default' directory in the filesystem");
  System.out.println("(or any lower, for partial tree) and should contain a _PFC._PS file");
  System.out.println("e.g. java PMconvert c:\\CAB\\Default c:\\Cabinet");
  System.out.println("Converts PaperMaster filesystem in sourceDirectory to normal");
  System.out.println("computer filesystem in destinationDirectory");
}
else {
  File poss=new File(args[0]+File.separator+"_PFC._PS");

  if (!poss.exists()) {  // "_PRF._PS" file in root directory
    System.out.println("Start directory does not contain _PFC._PS file.  Aborting.");
  }
  else {
    System.out.println("Converting from <"+args[0]+"> to <"+args[1]+"> WHICH WILL BE OVERWRITTEN");
    System.out.println("Type OK to proceed, or Q to exit");

    Scanner scanner = new Scanner( System.in );
    String input = scanner.nextLine();
    if (input.toUpperCase().equals("OK"))
      new PMconvert(args[0],args[1]);
    else
      System.out.println("Aborting");
  }
}

}
}
