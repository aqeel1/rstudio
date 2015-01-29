/*
 * FindReferences.cpp
 *
 * Copyright (C) 2009-12 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

#include "FindReferences.hpp"

#include <boost/foreach.hpp>

#include <boost/algorithm/string/split.hpp>

#include <core/FileSerializer.hpp>
#include <core/libclang/LibClang.hpp>

#include <session/SessionModuleContext.hpp>

#include "RSourceIndex.hpp"

// TODO: need to be able to vector in on the actual type name
// for multi-line references! (such that extent is always valid)

// TODO: populate the target search string in toolbar

// TODO: multi-file project searches

using namespace rstudio::core;
using namespace rstudio::core::libclang;

namespace rstudio {
namespace session {
namespace modules { 
namespace clang {

namespace {

struct FindReferencesData
{
   explicit FindReferencesData(const std::string& USR)
      : USR(USR)
   {
   }
   std::string USR;
   std::vector<CursorLocation> references;
};

CXChildVisitResult findReferencesVisitor(CXCursor cxCursor,
                                         CXCursor,
                                         CXClientData data)
{
   // get pointer to data struct
   FindReferencesData* pData = (FindReferencesData*)data;

   // reference to the cursor
   Cursor cursor(cxCursor);

   // continue with sibling if it's not from the main file
   SourceLocation location = cursor.getSourceLocation();
   if (!location.isFromMainFile())
      return CXChildVisit_Continue;

   // get referenced cursor
   Cursor referencedCursor = cursor.getReferenced();
   if (referencedCursor.isValid() && referencedCursor.isDeclaration())
   {
      // check for matching USR
      if (referencedCursor.getUSR() == pData->USR)
         pData->references.push_back(cursor.getLocation());
   }

   // recurse into namespaces, classes, etc.
   return CXChildVisit_Recurse;
}

class SourceMarkerGenerator
{
public:
   std::vector<module_context::SourceMarker> markersForCursorLocations(
              const std::vector<core::libclang::CursorLocation>& locations)
   {
      using namespace module_context;
      std::vector<SourceMarker> markers;

      BOOST_FOREACH(const libclang::CursorLocation& loc, locations)
      {
         // get file contents and use it to create the message
         std::size_t line = loc.line - 1;
         std::string message;
         const std::vector<std::string>& lines = fileContents(
                                                loc.filePath.absolutePath());
         if (line < lines.size())
            message = htmlMessage(loc, lines[line]);


         // create marker
         SourceMarker marker(SourceMarker::Usage,
                             loc.filePath,
                             loc.line,
                             loc.column,
                             core::html_utils::HTML(message, true),
                             true);

         // add it to the list
         markers.push_back(marker);
      }

      return markers;
   }

private:

   static std::string htmlMessage(const libclang::CursorLocation& loc,
                                  const std::string& message)
   {
      // attempt to highlight the location
      using namespace string_utils;
      unsigned col = loc.column - 1;
      if ((col + loc.extent) < message.length())
      {
         if (loc.extent == 0)
         {
            return "<strong>" + htmlEscape(message) + "</strong>";
         }
         else
         {
            std::ostringstream ostr;
            ostr << htmlEscape(message.substr(0, col));
            ostr << "<strong>";
            ostr << htmlEscape(message.substr(col, loc.extent));
            ostr << "</strong>";
            ostr << htmlEscape(message.substr(col + loc.extent));
            return ostr.str();
         }
      }
      else
      {
         return string_utils::htmlEscape(message);
      }
   }

   typedef std::map<std::string,std::vector<std::string> > SourceFileContentsMap;

   const std::vector<std::string>& fileContents(const std::string& filename)
   {
      // check cache
      SourceFileContentsMap::const_iterator it =
                                       sourceFileContents_.find(filename);
      if (it == sourceFileContents_.end())
      {
         // check unsaved files
         UnsavedFiles& unsavedFiles = rSourceIndex().unsavedFiles();
         unsigned numFiles = unsavedFiles.numUnsavedFiles();
         for (unsigned i = 0; i<numFiles; ++i)
         {
            CXUnsavedFile unsavedFile = unsavedFiles.unsavedFilesArray()[i];
            if (std::string(unsavedFile.Filename) == filename)
            {
               std::string contents(unsavedFile.Contents, unsavedFile.Length);
               std::vector<std::string> lines;
               boost::algorithm::split(lines,
                                       contents,
                                       boost::is_any_of("\n"));


               sourceFileContents_.insert(std::make_pair(filename, lines));
               it = sourceFileContents_.find(filename);
               break;
            }
         }

         // if we didn't get one then read it from disk
         if (it == sourceFileContents_.end())
         {
            std::vector<std::string> lines;
            Error error = readStringVectorFromFile(FilePath(filename),
                                                   &lines,
                                                   false);
            if (error)
               LOG_ERROR(error);

            // insert anyway to ensure it->second below works
            sourceFileContents_.insert(std::make_pair(filename, lines));
            it = sourceFileContents_.find(filename);
         }
      }

      // return reference to contents
      return it->second;
   }

private:
   SourceFileContentsMap sourceFileContents_;
};

} // anonymous namespace

core::Error findReferences(const core::libclang::FileLocation& location,
                           std::vector<core::libclang::CursorLocation>* pRefs)
{
   Cursor cursor = rSourceIndex().referencedCursorForFileLocation(location);
   if (!cursor.isValid() || !cursor.isDeclaration())
      return Success();

   // get it's USR (bail if it doesn't have one)
   std::string USR = cursor.getUSR();
   if (USR.empty())
      return Success();

   // now look for references in the current translation unit
   TranslationUnit tu = rSourceIndex().getTranslationUnit(
                                    location.filePath.absolutePath(), true);
   if (tu.empty())
      return Success();

   // visit the cursors and accumulate references
   FindReferencesData findUsagesData(USR);
   libclang::clang().visitChildren(tu.getCursor().getCXCursor(),
                                   findReferencesVisitor,
                                   (CXClientData)&findUsagesData);

   // copy the locations to the out parameter
   *pRefs = findUsagesData.references;

   return Success();

}

Error findUsages(const json::JsonRpcRequest& request,
                       json::JsonRpcResponse* pResponse)
{
   // get params
   std::string docPath;
   int line, column;
   Error error = json::readParams(request.params,
                                  &docPath,
                                  &line,
                                  &column);
   if (error)
      return error;

   // resolve the docPath if it's aliased
   FilePath filePath = module_context::resolveAliasedPath(docPath);

   // get the declaration cursor for this file location
   core::libclang::FileLocation location(filePath, line, column);

   // find the references
   std::vector<core::libclang::CursorLocation> usageLocations;
   error = findReferences(location, &usageLocations);
   if (error)
      return error;

   // produce source markers from cursor locations
   using namespace module_context;
   std::vector<SourceMarker> markers = SourceMarkerGenerator()
                                 .markersForCursorLocations(usageLocations);


   SourceMarkerSet markerSet("C++ Find Usages", markers);
   showSourceMarkers(markerSet, MarkerAutoSelectNone);

   return Success();
}


} // namespace clang
} // namespace modules
} // namesapce session
} // namespace rstudio

