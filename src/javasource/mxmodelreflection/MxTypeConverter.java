package mxmodelreflection;

import com.mendix.systemwideinterfaces.core.IDataType;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;

import mxmodelreflection.proxies.PrimitiveTypes;

public class MxTypeConverter {

  public PrimitiveTypes fromPrimitiveType(IMetaPrimitive.PrimitiveType primitiveType) {
    switch (primitiveType) {
      case String:
        return PrimitiveTypes.StringType;
      case AutoNumber:
        return PrimitiveTypes.AutoNumber;
      case Boolean:
        return PrimitiveTypes.BooleanType;
      case Decimal:
        return PrimitiveTypes.Decimal;
      case DateTime:
        return PrimitiveTypes.DateTime;
      case Enum:
        return PrimitiveTypes.EnumType;
      case HashString:
        return PrimitiveTypes.HashString;
      case Integer:
        return PrimitiveTypes.IntegerType;
      case Long:
        return PrimitiveTypes.LongType;
      case Binary:
        return null;
      default:
        return null;
    }
  }

  public PrimitiveTypes fromDatatype(IDataType dataType) {
    PrimitiveTypes type = null;

    switch (dataType.getType()) {
      case String:
        type = PrimitiveTypes.StringType;
        break;
      case AutoNumber:
        type = PrimitiveTypes.AutoNumber;
        break;
      case Boolean:
        type = PrimitiveTypes.BooleanType;
        break;
      case Datetime:
        type = PrimitiveTypes.DateTime;
        break;
      case Enumeration:
        type = PrimitiveTypes.EnumType;
        break;
      case HashString:
        type = PrimitiveTypes.HashString;
        break;
      case Integer:
        type = PrimitiveTypes.IntegerType;
        break;
      case Long:
        type = PrimitiveTypes.LongType;
        break;
      case Decimal:
        type = PrimitiveTypes.Decimal;
        break;
      case Object:
      case Binary:
      case Nothing:
      case Unknown:
      case AnyObject:
        break;
    }

    if (dataType.isList())
      type = PrimitiveTypes.ObjectList;
    else if (dataType.isMendixObject())
      type = PrimitiveTypes.ObjectType;

    return type;
  }

  public PrimitiveTypes fromString(String value) {
    return PrimitiveTypes.valueOf(value);
  }
}
