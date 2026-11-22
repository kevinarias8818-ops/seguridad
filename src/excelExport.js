import {
  formatDateTimeShort,
  getTimestampMillis,
  isSafePlateImageDataUrl,
  todayIsoDate,
} from './accessService.js';

export const REPORT_PERIODS = {
  LAST_24H: {
    id: 'LAST_24H',
    title: 'Últimas 24 horas',
    subtitle: 'Ingresos, salidas e imágenes de placa de las últimas 24 horas',
    hoursWindow: 24,
    daysWindow: 1,
    slug: '24h',
  },
  LAST_7D: {
    id: 'LAST_7D',
    title: 'Últimos 7 días (Semanal)',
    subtitle: 'Consolidado semanal completo de los últimos 7 días',
    hoursWindow: 24 * 7,
    daysWindow: 7,
    slug: '7dias',
  },
};

function isoDateDaysAgo(daysAgo, nowMs = Date.now()) {
  const d = new Date(nowMs);
  d.setDate(d.getDate() - daysAgo);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function formatFileStamp(nowMs = Date.now()) {
  const d = new Date(nowMs);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  const hh = String(d.getHours()).padStart(2, '0');
  const mm = String(d.getMinutes()).padStart(2, '0');
  return `${y}${m}${day}_${hh}${mm}`;
}

export function filterEntriesForPeriod(entries, periodKey, nowMs = Date.now()) {
  const period = REPORT_PERIODS[periodKey] || REPORT_PERIODS.LAST_24H;
  const cutoffMs = nowMs - period.hoursWindow * 3600 * 1000;
  const startIso = isoDateDaysAgo(period.daysWindow - 1, nowMs);
  const todayIso = todayIsoDate();

  return entries
    .filter((entry) => {
      const timestamps = [
        getTimestampMillis(entry.checkOutAt),
        getTimestampMillis(entry.checkInAt),
        getTimestampMillis(entry.updatedAt),
        getTimestampMillis(entry.createdAt),
      ].filter((ms) => ms > 0);

      const hasActivityInWindow = timestamps.some((ms) => ms >= cutoffMs);
      const entryDate = entry.entryDate || '';
      const inDateScope =
        period.id === 'LAST_24H'
          ? entryDate === todayIso
          : entryDate >= startIso && entryDate <= todayIso;

      return hasActivityInWindow || inDateScope;
    })
    .sort((a, b) => {
      const dateCmp = (b.entryDate || '').localeCompare(a.entryDate || '');
      if (dateCmp !== 0) return dateCmp;
      const tA =
        getTimestampMillis(a.checkOutAt) ||
        getTimestampMillis(a.checkInAt) ||
        getTimestampMillis(a.createdAt);
      const tB =
        getTimestampMillis(b.checkOutAt) ||
        getTimestampMillis(b.checkInAt) ||
        getTimestampMillis(b.createdAt);
      return tB - tA;
    });
}

function escapeXml(value) {
  const str = String(value ?? '');
  let out = '';
  for (let i = 0; i < str.length; i += 1) {
    const code = str.charCodeAt(i);
    if (
      code !== 0x9 &&
      code !== 0xa &&
      code !== 0xd &&
      (code < 0x20 || code === 0xfffe || code === 0xffff)
    ) {
      continue;
    }
    const ch = str[i];
    if (ch === '&') out += '&amp;';
    else if (ch === '<') out += '&lt;';
    else if (ch === '>') out += '&gt;';
    else if (ch === '"') out += '&quot;';
    else if (ch === "'") out += '&apos;';
    else out += ch;
  }
  return out;
}

function colRef(colZeroBased, rowOneBased) {
  let n = colZeroBased;
  let colName = '';
  while (n >= 0) {
    colName = String.fromCharCode(65 + (n % 26)) + colName;
    n = Math.floor(n / 26) - 1;
  }
  return `${colName}${rowOneBased}`;
}

function inlineCell(cellRef, text, styleIdx) {
  return `      <c r="${cellRef}" t="inlineStr" s="${styleIdx}"><is><t xml:space="preserve">${escapeXml(
    text
  )}</t></is></c>\n`;
}

function decodeDataUrlBytes(dataUrl) {
  if (!isSafePlateImageDataUrl(dataUrl)) return null;
  const trimmed = dataUrl.trim();
  const commaIdx = trimmed.indexOf(',');
  if (commaIdx <= 0) return null;
  const ext = trimmed.startsWith('data:image/png;base64,')
    ? 'png'
    : trimmed.startsWith('data:image/webp;base64,')
    ? 'webp'
    : 'jpg';
  try {
    const bin = atob(trimmed.slice(commaIdx + 1));
    if (!bin.length) return null;
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i += 1) {
      bytes[i] = bin.charCodeAt(i);
    }
    return { ext, bytes };
  } catch {
    return null;
  }
}

function statusLabel(status) {
  if (status === 'SALIDA') return 'Ya salió';
  if (status === 'INGRESO') return 'Ya ingresó';
  return 'Pendiente';
}

function visitorLabel(type) {
  if (type === 'PERSONAL') return 'Personal';
  if (type === 'PROVEEDOR') return 'Proveedor';
  if (type === 'CONTRATISTA') return 'Contratista';
  return 'Visitante';
}

function sourceLabel(src) {
  return src === 'CORREO_INSTITUCIONAL' ? 'Correo institucional' : 'Registro manual';
}

function buildEntriesSheetXml(entries, mediaList) {
  const headers = [
    '#',
    'Estado',
    'Fecha Autorizada',
    'Hora Programada',
    'Grado / Cargo',
    'Nombre Completo',
    'Cédula / Documento',
    'Tipo de Ingreso',
    'Placa',
    'Imagen de Placa',
    'Archivo de Imagen',
    'Dependencia / Unidad Destino',
    'Motivo',
    'Observaciones',
    'Origen',
    'Fecha y Hora de Ingreso',
    'Ingreso Registrado Por',
    'Fecha y Hora de Salida',
    'Salida Registrada Por',
    'Creado Por',
    'Fecha Creación',
    'Última Actualización',
  ];
  const colWidths = [
    6, 16, 16, 15, 16, 26, 18, 16, 14, 24, 24, 26, 28, 30, 20, 20, 22, 20, 22, 22, 18, 18,
  ];

  let xml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
  <cols>
`;
  colWidths.forEach((w, idx) => {
    xml += `    <col min="${idx + 1}" max="${idx + 1}" width="${w}" customWidth="1"/>\n`;
  });
  xml += '  </cols>\n  <sheetData>\n';
  xml += '    <row r="1" ht="28" customHeight="1">\n';
  headers.forEach((title, colIdx) => {
    xml += inlineCell(colRef(colIdx, 1), title, 1);
  });
  xml += '    </row>\n';

  if (entries.length === 0) {
    xml += '    <row r="2" ht="26" customHeight="1">\n';
    xml += inlineCell('A2', '—', 0);
    xml += inlineCell('B2', 'Sin registros en el período seleccionado', 0);
    xml += '    </row>\n';
  } else {
    entries.forEach((entry, index) => {
      const rowNum1 = index + 2;
      const rowIdx0 = rowNum1 - 1;
      const decoded = decodeDataUrlBytes(entry.plateImageData);
      const hasImage = Boolean(decoded);
      if (decoded) {
        mediaList.push({
          mediaIndex: mediaList.length + 1,
          rowZeroBased: rowIdx0,
          ext: decoded.ext,
          bytes: decoded.bytes,
        });
      }

      const rowHeight = hasImage ? 96 : 28;
      const status = entry.status || 'PENDIENTE';
      const statusStyle = status === 'SALIDA' ? 5 : status === 'INGRESO' ? 4 : 3;
      const checkInText = entry.checkInAt ? formatDateTimeShort(entry.checkInAt) : 'Pendiente';
      const checkOutText =
        status === 'SALIDA'
          ? entry.checkOutAt
            ? formatDateTimeShort(entry.checkOutAt)
            : 'Registrada'
          : status === 'INGRESO'
          ? 'En sitio'
          : 'Pendiente';

      const values = [
        String(index + 1),
        statusLabel(status),
        entry.entryDate || '',
        entry.expectedTime || 'Sin hora',
        entry.rank || '',
        entry.fullName || '',
        entry.documentNumber || '',
        visitorLabel(entry.visitorType),
        entry.plate || 'Peatonal',
        hasImage ? '' : 'Sin imagen adjunta',
        hasImage ? entry.plateImageName || 'placa.jpg' : '',
        entry.destinationUnit || '',
        entry.reason || '',
        entry.notes || '',
        sourceLabel(entry.source),
        checkInText,
        entry.checkInByName || '',
        checkOutText,
        entry.checkOutByName || '',
        entry.createdByName || '',
        entry.createdAt ? formatDateTimeShort(entry.createdAt) : '',
        entry.updatedAt ? formatDateTimeShort(entry.updatedAt) : '',
      ];

      xml += `    <row r="${rowNum1}" ht="${rowHeight}" customHeight="1">\n`;
      values.forEach((val, colIdx) => {
        xml += inlineCell(colRef(colIdx, rowNum1), val, colIdx === 1 ? statusStyle : 0);
      });
      xml += '    </row>\n';
    });
  }

  xml += '  </sheetData>\n';
  if (mediaList.length > 0) {
    xml += '  <drawing r:id="rId1"/>\n';
  }
  xml += '</worksheet>';
  return xml;
}

function buildSummarySheetXml(entries, period, generatedBy, unitId, imageCount, nowMs) {
  const pendingCount = entries.filter((e) => (e.status || 'PENDIENTE') === 'PENDIENTE').length;
  const inCount = entries.filter((e) => e.status === 'INGRESO').length;
  const outCount = entries.filter((e) => e.status === 'SALIDA').length;
  const startDate = isoDateDaysAgo(period.daysWindow - 1, nowMs);
  const endDate = todayIsoDate();
  const generatedAt = new Date(nowMs).toLocaleString('es-ES', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });

  const rows = [
    ['Control GUSEF CENOP — Reporte de Ingresos y Salidas', '', 2],
    ['Período seleccionado', period.title, 6],
    ['Cobertura de fechas', `${startDate} al ${endDate}`, 0],
    ['Unidad', unitId, 0],
    ['Generado por (Administrador)', generatedBy, 0],
    ['Fecha de generación', generatedAt, 0],
    ['', '', 0],
    ['Indicador', 'Cantidad', 1],
    ['Total de registros en el período', String(entries.length), 6],
    ['Pendientes por ingresar', String(pendingCount), 3],
    ['En sitio (Ya ingresó)', String(inCount), 4],
    ['Salidas completadas (Ya salió)', String(outCount), 5],
    ['Registros con imagen de placa adjunta', String(imageCount), 0],
  ];

  let xml = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <cols>
    <col min="1" max="1" width="38" customWidth="1"/>
    <col min="2" max="2" width="36" customWidth="1"/>
  </cols>
  <sheetData>
`;
  rows.forEach(([label, val, style], idx) => {
    const r = idx + 1;
    const ht = r === 1 ? 30 : 24;
    xml += `    <row r="${r}" ht="${ht}" customHeight="1">\n`;
    xml += inlineCell(`A${r}`, label, style);
    if (val !== '' || r === 8) {
      xml += inlineCell(`B${r}`, val, style);
    }
    xml += '    </row>\n';
  });
  xml += '  </sheetData>\n</worksheet>';
  return xml;
}

function buildContentTypesXml(hasDrawings, exts) {
  let extXml = '';
  if (exts.has('jpg')) {
    extXml += '  <Default Extension="jpg" ContentType="image/jpeg"/>\n';
  }
  if (exts.has('png')) {
    extXml += '  <Default Extension="png" ContentType="image/png"/>\n';
  }
  if (exts.has('webp')) {
    extXml += '  <Default Extension="webp" ContentType="image/webp"/>\n';
  }
  const drawingOverride = hasDrawings
    ? '  <Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>\n'
    : '';
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-officedocument.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
${extXml}  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
${drawingOverride}</Types>`;
}

function buildDrawingXml(mediaList) {
  const anchors = mediaList
    .map((m) => {
      const r = m.rowZeroBased;
      const id = m.mediaIndex + 1;
      return `  <xdr:twoCellAnchor editAs="oneCell">
    <xdr:from>
      <xdr:col>9</xdr:col>
      <xdr:colOff>38100</xdr:colOff>
      <xdr:row>${r}</xdr:row>
      <xdr:rowOff>38100</xdr:rowOff>
    </xdr:from>
    <xdr:to>
      <xdr:col>9</xdr:col>
      <xdr:colOff>1480000</xdr:colOff>
      <xdr:row>${r}</xdr:row>
      <xdr:rowOff>1140000</xdr:rowOff>
    </xdr:to>
    <xdr:pic>
      <xdr:nvPicPr>
        <xdr:cNvPr id="${id}" name="Placa_${m.mediaIndex}"/>
        <xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr>
      </xdr:nvPicPr>
      <xdr:blipFill>
        <a:blip r:embed="rId${m.mediaIndex}"/>
        <a:stretch><a:fillRect/></a:stretch>
      </xdr:blipFill>
      <xdr:spPr>
        <a:xfrm><a:off x="0" y="0"/><a:ext cx="1441900" cy="1101900"/></a:xfrm>
        <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
      </xdr:spPr>
    </xdr:pic>
    <xdr:clientData/>
  </xdr:twoCellAnchor>`;
    })
    .join('\n');

  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
          xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
${anchors}
</xdr:wsDr>`;
}

function buildDrawingRelsXml(mediaList) {
  const rels = mediaList
    .map(
      (m) =>
        `  <Relationship Id="rId${m.mediaIndex}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/plate_${m.mediaIndex}.${m.ext}"/>`
    )
    .join('\n');
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
${rels}
</Relationships>`;
}

const ROOT_RELS_XML = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>`;

const WORKBOOK_XML = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Ingresos y Salidas" sheetId="1" r:id="rId1"/>
    <sheet name="Resumen" sheetId="2" r:id="rId2"/>
  </sheets>
</workbook>`;

const WORKBOOK_RELS_XML = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>`;

const SHEET1_RELS_XML = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/>
</Relationships>`;

const STYLES_XML = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="6">
    <font><sz val="11"/><color rgb="FF0F172A"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
    <font><b/><sz val="14"/><color rgb="FF0F172A"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FF92400E"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FF065F46"/><name val="Calibri"/></font>
    <font><b/><sz val="11"/><color rgb="FF075985"/><name val="Calibri"/></font>
  </fonts>
  <fills count="8">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF0F172A"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFFEF3C7"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFD1FAE5"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFE0F2FE"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFF1F5F9"/></patternFill></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FFFFFFFF"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFCBD5E1"/></left>
      <right style="thin"><color rgb="FFCBD5E1"/></right>
      <top style="thin"><color rgb="FFCBD5E1"/></top>
      <bottom style="thin"><color rgb="FFCBD5E1"/></bottom>
    </border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="7">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment vertical="center"/></xf>
    <xf numFmtId="0" fontId="3" fillId="3" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="4" fillId="4" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="5" fillId="5" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center" wrapText="1"/></xf>
    <xf numFmtId="0" fontId="0" fillId="6" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
  </cellXfs>
</styleSheet>`;

// Minimal zero-dependency PKZIP (STORE method 0 + CRC32) writer for valid OOXML .xlsx
const CRC_TABLE = (() => {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i += 1) {
    let c = i;
    for (let k = 0; k < 8; k += 1) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[i] = c >>> 0;
  }
  return table;
})();

function crc32(bytes) {
  let c = 0xffffffff;
  for (let i = 0; i < bytes.length; i += 1) {
    c = CRC_TABLE[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  }
  return (c ^ 0xffffffff) >>> 0;
}

function buildZipStore(files) {
  const encoder = new TextEncoder();
  const localParts = [];
  const centralParts = [];
  let offset = 0;

  for (const file of files) {
    const nameBytes = encoder.encode(file.name);
    const data = typeof file.content === 'string' ? encoder.encode(file.content) : file.content;
    const crc = crc32(data);

    const localHeader = new Uint8Array(30 + nameBytes.length);
    const lv = new DataView(localHeader.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(6, 0x0800, true); // UTF-8
    lv.setUint16(8, 0, true); // STORE
    lv.setUint16(10, 0, true);
    lv.setUint16(12, 0x5421, true);
    lv.setUint32(14, crc, true);
    lv.setUint32(18, data.length, true);
    lv.setUint32(22, data.length, true);
    lv.setUint16(26, nameBytes.length, true);
    lv.setUint16(28, 0, true);
    localHeader.set(nameBytes, 30);

    const centralHeader = new Uint8Array(46 + nameBytes.length);
    const cv = new DataView(centralHeader.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(8, 0x0800, true);
    cv.setUint16(10, 0, true);
    cv.setUint16(12, 0, true);
    cv.setUint16(14, 0x5421, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, data.length, true);
    cv.setUint32(24, data.length, true);
    cv.setUint16(28, nameBytes.length, true);
    cv.setUint16(30, 0, true);
    cv.setUint16(32, 0, true);
    cv.setUint16(34, 0, true);
    cv.setUint16(36, 0, true);
    cv.setUint32(38, 0, true);
    cv.setUint32(42, offset, true);
    centralHeader.set(nameBytes, 46);

    localParts.push(localHeader, data);
    centralParts.push(centralHeader);
    offset += localHeader.length + data.length;
  }

  let centralSize = 0;
  for (const part of centralParts) centralSize += part.length;

  const eocd = new Uint8Array(22);
  const ev = new DataView(eocd.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(4, 0, true);
  ev.setUint16(6, 0, true);
  ev.setUint16(8, files.length, true);
  ev.setUint16(10, files.length, true);
  ev.setUint32(12, centralSize, true);
  ev.setUint32(16, offset, true);
  ev.setUint16(20, 0, true);

  return new Blob([...localParts, ...centralParts, eocd], {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
}

export function buildAccessExcelBlob(entries, periodKey, currentUser, nowMs = Date.now()) {
  if (!currentUser || currentUser.role !== 'ADMIN' || currentUser.active !== true) {
    throw new Error('Solo el rol Administrador puede descargar reportes Excel.');
  }
  const period = REPORT_PERIODS[periodKey] || REPORT_PERIODS.LAST_24H;
  const filtered = filterEntriesForPeriod(entries, period.id, nowMs);
  const mediaList = [];
  const sheet1Xml = buildEntriesSheetXml(filtered, mediaList);
  const generatedBy = (currentUser.displayName || currentUser.email || 'Administrador').trim();
  const unitId = currentUser.unitId || 'UNIDAD_PRINCIPAL';
  const sheet2Xml = buildSummarySheetXml(
    filtered,
    period,
    generatedBy,
    unitId,
    mediaList.length,
    nowMs
  );

  const hasDrawings = mediaList.length > 0;
  const exts = new Set(mediaList.map((m) => m.ext));

  const files = [
    { name: '[Content_Types].xml', content: buildContentTypesXml(hasDrawings, exts) },
    { name: '_rels/.rels', content: ROOT_RELS_XML },
    { name: 'xl/workbook.xml', content: WORKBOOK_XML },
    { name: 'xl/_rels/workbook.xml.rels', content: WORKBOOK_RELS_XML },
    { name: 'xl/styles.xml', content: STYLES_XML },
    { name: 'xl/worksheets/sheet1.xml', content: sheet1Xml },
    { name: 'xl/worksheets/sheet2.xml', content: sheet2Xml },
  ];

  if (hasDrawings) {
    files.push({ name: 'xl/worksheets/_rels/sheet1.xml.rels', content: SHEET1_RELS_XML });
    files.push({ name: 'xl/drawings/drawing1.xml', content: buildDrawingXml(mediaList) });
    files.push({
      name: 'xl/drawings/_rels/drawing1.xml.rels',
      content: buildDrawingRelsXml(mediaList),
    });
    for (const m of mediaList) {
      files.push({ name: `xl/media/plate_${m.mediaIndex}.${m.ext}`, content: m.bytes });
    }
  }

  return {
    blob: buildZipStore(files),
    fileName: `ControlGUSEF_${period.slug}_${formatFileStamp(nowMs)}.xlsx`,
    entryCount: filtered.length,
    imageCount: mediaList.length,
    period,
  };
}

export function downloadBlobFile(blob, fileName) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  setTimeout(() => {
    document.body.removeChild(anchor);
    URL.revokeObjectURL(url);
  }, 1500);
}
