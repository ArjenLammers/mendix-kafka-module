package mxmodelreflection;

import java.util.ArrayList;
import java.util.List;

import mxmodelreflection.proxies.MxObjectMember;
import mxmodelreflection.proxies.MxObjectReference;
import mxmodelreflection.proxies.MxObjectType;
import mxmodelreflection.proxies.Status;
import mxmodelreflection.proxies.Token;
import mxmodelreflection.proxies.TokenType;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.core.objectmanagement.member.MendixObjectReference;
import com.mendix.core.objectmanagement.member.MendixObjectReferenceSet;
import com.mendix.logging.ILogNode;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation.AssociationOwner;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

public class TokenReplacer
{
	protected static ILogNode _logger = Core.getLogger("TokenReplacer");
	public static List<IMendixObject> validateTokens(IContext context, String text, List<IMendixObject> tokenList) throws CoreException
	{
		if (tokenList == null)
		{
			throw new CoreException("The given token list is empty.");
		}
		if (text == null)
		{
			throw new CoreException("The source text is empty.");
		}
		
		List<IMendixObject> missingTokens = new ArrayList<IMendixObject>();
		for (IMendixObject token : tokenList)
		{
			if( !isTokenPresent(context, text, token) )
				missingTokens.add(token);
		}

		return missingTokens;
	}
	
	public static String replaceTokens(IContext context, String text, List<IMendixObject> tokenList, IMendixObject replacementObject) throws CoreException
	{
		if (tokenList == null)
		{
			throw new CoreException("The given token list is empty.");
		}
		if (text != null)
		{
			for (IMendixObject token : tokenList)
			{
				text = replaceToken(context, text, token, replacementObject);
			}
		}
		else
			_logger.debug("No text found to replace any tokens");
		
		return text;
	}

	public static String replaceToken(IContext context, String text, IMendixObject token, IMendixObject replacementObject) throws CoreException
	{
		if (token == null)
		{
			throw new CoreException("The given token is empty.");
		}
		if (text == null)
		{
			throw new CoreException("The source text is empty.");
		}

		String tokenValue = (String) token.getValue(context, Token.MemberNames.CombinedToken.toString());
		if (!Status.Valid.toString().equals(token.getValue(context, Token.MemberNames.Status.toString())))
		{
			throw new CoreException("The token: " + tokenValue + " is not valid, only valid tokens can be replaced.");
		}

		try
		{
			IMendixObjectMember<?> replacementMember = null;
			if (replacementObject != null)
			{
				TokenType tokenType = TokenType.valueOf((String) token.getValue(context, Token.MemberNames.TokenType.toString()));
				if (tokenType == TokenType.Attribute)
				{

					IMendixObject member = Core.retrieveId(context, (IMendixIdentifier) token.getValue(context, Token.MemberNames.Token_MxObjectMember.toString()));
					IMendixObject selectedObjType = Core.retrieveId(context, (IMendixIdentifier) token.getValue(context, Token.MemberNames.Token_MxObjectType_Start.toString()));

					if (!replacementObject.isInstanceOf((String) selectedObjType.getValue(context, MxObjectType.MemberNames.CompleteName.toString())))
					{
						throw new CoreException("wrong object type in token: " + tokenValue + " The object should be of type: " + selectedObjType.getValue(context, MxObjectType.MemberNames.CompleteName.toString()) + " but is of type: " + replacementObject.getType());
					}

					replacementMember = replacementObject.getMember((String) member.getValue(context, MxObjectMember.MemberNames.AttributeName.toString()));
				}
				else
				{
					IMendixObject reference = Core.retrieveId(context, (IMendixIdentifier) token.getValue(context, Token.MemberNames.Token_MxObjectReference.toString()));
					IMendixObject objectTypeStart = Core.retrieveId(context, (IMendixIdentifier) token.getValue(context, Token.MemberNames.Token_MxObjectType_Start.toString()));
					IMendixObject objectTypeReference = Core.retrieveId(context, (IMendixIdentifier) token.getValue(context, Token.MemberNames.Token_MxObjectType_Referenced.toString()));
					IMendixObject member = Core.retrieveId(context, (IMendixIdentifier) token.getValue(context, Token.MemberNames.Token_MxObjectMember.toString()));
					String parentName = (String) objectTypeStart.getValue(context, MxObjectType.MemberNames.CompleteName.toString());
					if(replacementObject.isInstanceOf(parentName))
					{
						String associationName = (String) reference.getValue(context, MxObjectReference.MemberNames.CompleteName.toString());
						if (replacementObject.getMetaObject().getMetaAssociationParent(associationName) != null)
						{
							final var association = replacementObject.getMember(associationName);
							final var refObjectId = association instanceof MendixObjectReference ?
								((MendixObjectReference) association).getValue(context) :
								((MendixObjectReferenceSet) association).getValue(context).stream().findFirst().orElse(null);
							if (refObjectId != null)
							{
								IMendixObject refObj = Core.retrieveId(context, refObjectId);

								replacementMember = refObj.getMember((String) member.getValue(context, MxObjectMember.MemberNames.AttributeName.toString()));
							}
						}
						else
						{
							List<IMendixObject> result = Core.createXPathQuery("//" + objectTypeReference.getValue(context, MxObjectType.MemberNames.CompleteName.toString()) + "[" + associationName + "='" + replacementObject.getId().toLong() + "']").execute(context);
							if (result.size() > 0)
							{
								IMendixObject rsObject = result.get(0);
								replacementMember = rsObject.getMember((String) member.getValue(context, MxObjectMember.MemberNames.AttributeName.toString()));
							}
						}
					}
					else
					{
						throw new CoreException("wrong object type in token: " + tokenValue + ", Expecting type: " + parentName + " received type: " +  replacementObject.getType());
					}
				}
			}

			String replacementValue = DataParser.getStringValue(replacementMember, (String) token.getValue(context, Token.MemberNames.DisplayPattern.toString()), context);
			_logger.debug("Trying to replcace the token: "+tokenValue+" with the parsed value: " +replacementValue);
			if (replacementValue == null)
			{
				replacementValue = "";
			}
			String returnText = text.replace(tokenValue, replacementValue);

			return returnText;
		} 
		catch (Exception e)
		{
			_logger.error(e.getMessage(), e);
		}

		return text;
	}
	public static boolean isTokenPresent(IContext context, String text, IMendixObject token) throws CoreException
	{
		if (token == null)
		{
			throw new CoreException("The given token is empty.");
		}
		if (text == null)
		{
			throw new CoreException("The source text is empty.");
		}

		Boolean tokenOptional = (Boolean) token.getValue(context, Token.MemberNames.IsOptional.toString());
		String tokenValue = (String) token.getValue(context, Token.MemberNames.CombinedToken.toString());
		if (!Status.Valid.toString().equals(token.getValue(context, Token.MemberNames.Status.toString())))
		{
			throw new CoreException("The token: " + tokenValue + " is not valid, only valid tokens can be replaced.");
		}

		try
		{
			int tokenPosition = text.indexOf(tokenValue);
			_logger.debug("Token: " + tokenValue+" located at position: " + tokenPosition);

			return tokenOptional || tokenPosition >= 0;
		} 
		catch (Exception e)
		{
			_logger.error(e.getMessage(), e);
		}

		return tokenOptional;
	}

}
