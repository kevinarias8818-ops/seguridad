export const VISITOR_TYPES = [
  { value: 'PERSONAL', label: 'Personal' },
  { value: 'VISITANTE', label: 'Visitante' },
  { value: 'PROVEEDOR', label: 'Proveedor' },
  { value: 'CONTRATISTA', label: 'Contratista' },
];

export function parseVisitorType(raw = '') {
  const normalized = raw.trim().toLowerCase();
  if (
    normalized.includes('personal') ||
    normalized.includes('funcionario') ||
    normalized.includes('militar') ||
    normalized.includes('policia') ||
    normalized.includes('policía') ||
    normalized.includes('planta')
  ) {
    return 'PERSONAL';
  }
  if (
    normalized.includes('proveedor') ||
    normalized.includes('domicilio') ||
    normalized.includes('mensajeria') ||
    normalized.includes('mensajería') ||
    normalized.includes('entrega')
  ) {
    return 'PROVEEDOR';
  }
  if (
    normalized.includes('contratista') ||
    normalized.includes('tecnico') ||
    normalized.includes('técnico') ||
    normalized.includes('mantenimiento') ||
    normalized.includes('obra')
  ) {
    return 'CONTRATISTA';
  }
  return 'VISITANTE';
}

export const SAMPLE_INSTITUTIONAL_EMAIL = `Asunto: Autorización de ingresos - Turno del día

Grado: Tte.
Nombre: Carlos Alberto Mendoza
Cédula: 1020458912
Placa: KLM-482
Dependencia: Comandancia - Bloque B
Motivo: Reunión de coordinación operativa
Tipo: Personal
Hora programada: 08:30
Observaciones: Ingresa por garita norte con equipo portátil
---
Grado: Ing.
Nombre: Laura Sofía Gómez
Cédula: 52987412
Placa: Peatonal
Dependencia: Sala de Sistemas - Piso 3
Motivo: Mantenimiento preventivo de red
Tipo: Contratista
Hora programada: 09:00
Observaciones: Requiere acompañamiento de seguridad
---
Sr. | Javier Ramírez | 79654321 | WXY-905 | Logística y Bodega 4 | Entrega de suministros | Proveedor | 10:15 | Descargue autorizado`;

const RECOGNIZED_KEYS = new Set([
  'grado',
  'rango',
  'cargo',
  'tratamiento',
  'grado/nombre',
  'grado / nombre',
  'nombre',
  'nombres',
  'visitante',
  'personal',
  'persona',
  'empleado',
  'name',
  'cedula',
  'cédula',
  'cc',
  'documento',
  'doc',
  'dni',
  'id',
  'identificacion',
  'identificación',
  'placa',
  'vehiculo',
  'vehículo',
  'matricula',
  'matrícula',
  'dependencia',
  'unidad',
  'destino',
  'area',
  'área',
  'torre',
  'apartamento',
  'apto',
  'oficina',
  'casa',
  'motivo',
  'motivo/dependencia',
  'razon',
  'razón',
  'actividad',
  'tipo',
  'categoria',
  'categoría',
  'hora',
  'horario',
  'hora esperada',
  'hora prevista',
  'hora programada',
  'nota',
  'notas',
  'observacion',
  'observación',
  'observaciones',
]);

function normalizePlate(raw = '') {
  const trimmed = raw.trim();
  const lower = trimmed.toLowerCase();
  if (!trimmed || lower === 'peatonal' || lower === 'n/a' || lower === 'ninguna' || trimmed === '-') {
    return '';
  }
  return trimmed.toUpperCase().slice(0, 20);
}

function parseKeyValueBlock(lines, targetDateIso) {
  let rank = '';
  let fullName = '';
  let documentNumber = '';
  let plate = '';
  let destinationUnit = '';
  let reason = '';
  let visitorType = 'VISITANTE';
  let expectedTime = '';
  let notes = '';

  for (const line of lines) {
    const colonIdx = line.indexOf(':');
    if (colonIdx <= 0) continue;
    const key = line.slice(0, colonIdx).trim().toLowerCase();
    const value = line.slice(colonIdx + 1).trim();
    if (!value) continue;

    switch (key) {
      case 'grado':
      case 'rango':
      case 'cargo':
      case 'tratamiento':
        rank = value.slice(0, 40);
        break;
      case 'grado/nombre':
      case 'grado / nombre': {
        const match = value.match(/^(\S+)\s+(.+)$/);
        if (match && match[1].length <= 15) {
          rank = match[1].slice(0, 40);
          fullName = match[2].trim().slice(0, 120);
        } else {
          fullName = value.slice(0, 120);
        }
        break;
      }
      case 'nombre':
      case 'nombres':
      case 'visitante':
      case 'personal':
      case 'persona':
      case 'empleado':
      case 'name':
        fullName = value.slice(0, 120);
        break;
      case 'cedula':
      case 'cédula':
      case 'cc':
      case 'documento':
      case 'doc':
      case 'dni':
      case 'id':
      case 'identificacion':
      case 'identificación':
        documentNumber = value.slice(0, 40);
        break;
      case 'placa':
      case 'vehiculo':
      case 'vehículo':
      case 'matricula':
      case 'matrícula':
        plate = normalizePlate(value);
        break;
      case 'dependencia':
      case 'unidad':
      case 'destino':
      case 'area':
      case 'área':
      case 'torre':
      case 'apartamento':
      case 'apto':
      case 'oficina':
      case 'casa':
        destinationUnit = value.slice(0, 80);
        break;
      case 'motivo':
      case 'razon':
      case 'razón':
      case 'actividad':
        reason = value.slice(0, 160);
        break;
      case 'motivo/dependencia': {
        const slashIdx = value.indexOf('/');
        if (slashIdx >= 0) {
          reason = value.slice(0, slashIdx).trim().slice(0, 160);
          destinationUnit = value.slice(slashIdx + 1).trim().slice(0, 80);
        } else {
          destinationUnit = value.slice(0, 80);
        }
        break;
      }
      case 'tipo':
      case 'categoria':
      case 'categoría':
        visitorType = parseVisitorType(value);
        break;
      case 'hora':
      case 'horario':
      case 'hora esperada':
      case 'hora prevista':
      case 'hora programada':
        expectedTime = value.slice(0, 20);
        break;
      case 'nota':
      case 'notas':
      case 'observacion':
      case 'observación':
      case 'observaciones':
        notes = value.slice(0, 300);
        break;
      default:
        break;
    }
  }

  if (fullName.trim().length < 2) return null;

  return {
    entryDate: targetDateIso,
    rank,
    fullName: fullName.trim(),
    documentNumber: (documentNumber || 'S/D').slice(0, 40),
    plate,
    destinationUnit: (destinationUnit || 'Dependencia Principal').slice(0, 80),
    reason,
    visitorType,
    expectedTime,
    source: 'CORREO_INSTITUCIONAL',
    notes,
  };
}

function parseDelimitedLine(line, targetDateIso) {
  let delimiter = null;
  if (line.includes('|')) delimiter = '|';
  else if (line.includes(';')) delimiter = ';';
  else if (line.includes('\t')) delimiter = '\t';
  else if ((line.match(/,/g) || []).length >= 2) delimiter = ',';
  if (!delimiter) return null;

  const parts = line.split(delimiter).map((p) => p.trim());
  if (parts.length < 2) return null;
  const firstLower = parts[0].toLowerCase();
  if (firstLower === 'nombre' || firstLower === 'grado') return null;

  const hasRankColumn = parts.length >= 8;
  const rank = hasRankColumn ? parts[0].slice(0, 40) : '';
  const offset = hasRankColumn ? 1 : 0;

  const fullName = (parts[offset] || '').slice(0, 120);
  if (fullName.length < 2) return null;

  const documentNumber = (parts[offset + 1] || 'S/D').slice(0, 40) || 'S/D';
  const plate = normalizePlate(parts[offset + 2] || '');
  const destinationUnit =
    (parts[offset + 3] || 'Dependencia Principal').slice(0, 80) || 'Dependencia Principal';

  const reason = hasRankColumn ? (parts[offset + 4] || '').slice(0, 160) : '';
  const typeIdx = hasRankColumn ? offset + 5 : offset + 4;
  const visitorType = parts[typeIdx] ? parseVisitorType(parts[typeIdx]) : 'VISITANTE';
  const expectedTime = (parts[typeIdx + 1] || '').slice(0, 20);
  const notes = (parts[typeIdx + 2] || '').slice(0, 300);

  return {
    entryDate: targetDateIso,
    rank,
    fullName,
    documentNumber,
    plate,
    destinationUnit,
    reason,
    visitorType,
    expectedTime,
    source: 'CORREO_INSTITUCIONAL',
    notes,
  };
}

export function parseInstitutionalEmail(rawText, targetDateIso) {
  const cleaned = (rawText || '').replace(/\r\n/g, '\n').trim();
  if (!cleaned) return [];

  const blocks = cleaned
    .split(/\n\s*-{3,}\s*\n|\n{2,}/)
    .map((b) => b.trim())
    .filter(Boolean);

  const results = [];
  for (const block of blocks) {
    const lines = block
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean);

    const hasKeyValue = lines.some((line) => {
      const idx = line.indexOf(':');
      if (idx <= 0) return false;
      return RECOGNIZED_KEYS.has(line.slice(0, idx).trim().toLowerCase());
    });

    if (hasKeyValue) {
      const parsed = parseKeyValueBlock(lines, targetDateIso);
      if (parsed) results.push(parsed);
    } else {
      for (const line of lines) {
        if (/^(Asunto|De|Para|Fecha):/i.test(line)) continue;
        const parsed = parseDelimitedLine(line, targetDateIso);
        if (parsed) results.push(parsed);
      }
    }
  }
  return results;
}
