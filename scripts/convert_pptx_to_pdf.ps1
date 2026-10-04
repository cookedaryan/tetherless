$pptxPath = "C:\Users\aryan\Projects\tetherless\docs\tetherless_presentation.pptx"
$pdfPath = "C:\Users\aryan\Projects\tetherless\docs\tetherless_presentation.pdf"

Write-Host "Opening PowerPoint to convert $pptxPath to PDF..."

try {
    $pptApp = New-Object -ComObject PowerPoint.Application
    # Open: FileName, ReadOnly, Untitled, WithWindow (msoFalse = 0)
    $presentation = $pptApp.Presentations.Open($pptxPath, -1, 0, 0)
    # ppSaveAsPDF = 32
    $presentation.SaveAs($pdfPath, 32)
    $presentation.Close()
    $pptApp.Quit()
    [System.Runtime.Interopservices.Marshal]::ReleaseComObject($presentation) | Out-Null
    [System.Runtime.Interopservices.Marshal]::ReleaseComObject($pptApp) | Out-Null
    Write-Host "Successfully converted via PowerPoint COM: $pdfPath"
} catch {
    Write-Error "PowerPoint conversion failed: $_"
    exit 1
}
